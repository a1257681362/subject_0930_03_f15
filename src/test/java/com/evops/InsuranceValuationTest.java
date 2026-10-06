package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物保险估值管理（museum-15）规则验收：
 * 估值来源/评估机构/币种/有效期随版本固化；金额修订生成新版本并关联保险批单、
 * 双人审批（同人复核拒绝）；借展期间沿用签约时快照；过期仅提醒不冻结归还；
 * 财务只见金额/币种/有效期与凭证，公众/普通馆员拒绝；缺权收紧时变更留痕
 * 在服务入口拒绝，换调用地址同样不放行。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-insurance;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InsuranceValuationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void insurance_valuation_rules_hold() throws Exception {
        long relicA = createRelic("IV-A", "THREE", "MAIN");
        long relicB = createRelic("IV-B", "THREE", "MAIN");

        // ========== 一、初始登记 V1：来源/机构/币种/有效期/凭证随版本固化 ==========
        JsonNode reg = dataOf(postJson("/api/relics/" + relicA + "/insurance-valuations",
                registerBody("200000", "CNY", "市场比较法", "华瑞保险公估",
                        "2026-01-01T00:00:00", "2027-12-31T00:00:00", "保险管理员甲")));
        String valNo = reg.get("valuationNo").asText();
        assertTrue(valNo.startsWith("IV-"));
        assertNotNull(reg.get("currentVersionId"));

        // 一件文物只能一份估值：重复登记拒绝，金额变化必须走修订
        expectFailPost("/api/relics/" + relicA + "/insurance-valuations",
                registerBody("210000", "CNY", "市场比较法", "华瑞保险公估",
                        "2026-01-01T00:00:00", "2027-12-31T00:00:00", "甲"),
                "必须走修订新版本");

        // 保险管理员看全字段
        JsonNode adminView = dataOf(getJson(
                "/api/relics/" + relicA + "/insurance-valuation?role=INSURANCE_ADMIN"));
        JsonNode current = adminView.get("current");
        assertEquals(1, current.get("versionNo").asInt());
        assertEquals("APPROVED", current.get("status").asText());
        assertEquals("市场比较法", current.get("valuationSource").asText());
        assertEquals("华瑞保险公估", current.get("appraiserOrg").asText());
        assertEquals("CNY", current.get("currency").asText());
        assertEquals(1, current.get("vouchers").size());
        assertEquals("评估报告V1.pdf", current.get("vouchers").get(0).get("fileName").asText());

        // ========== 二、字段级访问：财务只见金额/币种/有效期与凭证 ==========
        JsonNode financeView = dataOf(getJson(
                "/api/relics/" + relicA + "/insurance-valuation?role=FINANCE"));
        JsonNode fCurrent = financeView.get("current");
        assertEquals(0, fCurrent.get("amount").decimalValue()
                .compareTo(new java.math.BigDecimal("200000")));
        assertEquals("CNY", fCurrent.get("currency").asText());
        assertNotNull(fCurrent.get("validUntil"));
        assertEquals(1, fCurrent.get("vouchers").size());
        // 来源/评估机构/报告编号/批单/签署人等敏感字段对财务全部不可见
        assertNull(fCurrent.get("valuationSource"));
        assertNull(fCurrent.get("appraiserOrg"));
        assertNull(fCurrent.get("reportNo"));
        assertNull(fCurrent.get("endorsementNo"));
        assertNull(fCurrent.get("proposerName"));
        assertNull(fCurrent.get("reviewerName"));

        // 公众与普通馆员不能访问敏感字段（未声明角色同样拒绝）
        expectFailGet("/api/relics/" + relicA + "/insurance-valuation?role=PUBLIC",
                "无权访问文物保险估值敏感信息");
        expectFailGet("/api/relics/" + relicA + "/insurance-valuation?role=LIBRARIAN",
                "无权访问文物保险估值敏感信息");
        expectFailGet("/api/relics/" + relicA + "/insurance-valuation",
                "无权访问文物保险估值敏感信息");
        // 借展绑定口径查询同样按角色拒绝
        expectFailGet("/api/borrow-applications/999/insurance-valuation?role=PUBLIC",
                "无权访问文物保险估值敏感信息");

        // ========== 三、金额修订：新版本 + 保险批单 + 双人审批 ==========
        // 修订必须关联保险批单号
        expectFailPost("/api/relics/" + relicA + "/insurance-valuations/revisions",
                reviseBodyNoEndorsement(),
                "金额修订必须关联保险批单号");

        // 有效期截止必须晚于生效时间
        expectFailPost("/api/relics/" + relicA + "/insurance-valuations/revisions",
                reviseBody("230000", "CNY", "市场比较法", "华瑞保险公估",
                        "2028-01-01T00:00:00", "2027-01-01T00:00:00",
                        "保险管理员甲", "END-2026-001"),
                "有效期截止时间必须晚于生效时间");

        // 先签约借展并出库（见第四节），再演示在途修订
        long appId = borrowApproveLend(relicA);

        JsonNode v2node = dataOf(postJson(
                "/api/relics/" + relicA + "/insurance-valuations/revisions",
                reviseBody("230000", "CNY", "市场比较法", "华瑞保险公估",
                        "2026-10-01T00:00:00", "2028-09-30T00:00:00",
                        "保险管理员甲", "END-2026-001")));
        long v2Id = v2node.get("id").asLong();
        assertEquals(2, v2node.get("versionNo").asInt());
        assertEquals("PENDING_APPROVAL", v2node.get("status").asText());
        assertEquals("END-2026-001", v2node.get("endorsementNo").asText());

        // 待复核期间不能重复提出修订
        expectFailPost("/api/relics/" + relicA + "/insurance-valuations/revisions",
                reviseBody("240000", "CNY", "市场比较法", "华瑞保险公估",
                        "2026-10-01T00:00:00", "2028-09-30T00:00:00",
                        "保险管理员甲", "END-2026-002"),
                "待双人复核");

        // 双人审批：复核人不能与提出人同一人
        expectFail(postJson("/api/insurance-valuation-versions/" + v2Id + "/review",
                "{\"reviewer\":\"保险管理员甲\",\"decision\":\"APPROVE\"}"),
                "复核人不能与修订提出人为同一人");
        // 驳回必须填意见
        expectFail(postJson("/api/insurance-valuation-versions/" + v2Id + "/review",
                "{\"reviewer\":\"财务复核乙\",\"decision\":\"REJECT\"}"),
                "驳回复核必须填写意见");
        // 非同人复核通过：V2 生效，V1 置 SUPERSEDED
        expectOk(postJson("/api/insurance-valuation-versions/" + v2Id + "/review",
                "{\"reviewer\":\"财务复核乙\",\"decision\":\"APPROVE\",\"opinion\":\"批单与新报告核验无误\"}"));

        JsonNode afterReview = dataOf(getJson(
                "/api/relics/" + relicA + "/insurance-valuation?role=INSURANCE_ADMIN"));
        assertEquals(2, afterReview.get("current").get("versionNo").asInt());
        assertEquals("APPROVED", afterReview.get("current").get("status").asText());
        JsonNode oldV1 = afterReview.get("versions").get(1);
        assertEquals(1, oldV1.get("versionNo").asInt());
        assertEquals("SUPERSEDED", oldV1.get("status").asText());
        assertNotNull(oldV1.get("supersededAt"));

        // ========== 四、借展期间沿用签约时快照（V1），不因修订改写 ==========
        JsonNode binding = dataOf(getJson(
                "/api/borrow-applications/" + appId + "/insurance-valuation?role=INSURANCE_ADMIN"));
        assertTrue(binding.get("bound").asBoolean());
        assertEquals(1, binding.get("boundVersion").get("versionNo").asInt());
        assertEquals(0, binding.get("boundVersion").get("amount").decimalValue()
                .compareTo(new java.math.BigDecimal("200000")));
        assertEquals(2, binding.get("currentVersionNo").asInt());
        assertFalse(binding.get("followsSignSnapshot").asBoolean(),
                "当前已是 V2，但借展口径仍沿用签约时 V1");
        // 财务查借展口径同样只见金额等白名单字段
        JsonNode bindingFinance = dataOf(getJson(
                "/api/borrow-applications/" + appId + "/insurance-valuation?role=FINANCE"));
        assertEquals(0, bindingFinance.get("boundVersion").get("amount").decimalValue()
                .compareTo(new java.math.BigDecimal("200000")));
        assertNull(bindingFinance.get("boundVersion").get("valuationSource"));

        // V2 已生效，文物仍在借（LENT）——归还原口径沿用签约快照，正常完成
        expectOk(postJson("/api/borrow-applications/" + appId + "/return",
                "{\"returnedBy\":\"接收员丙\",\"returnCondition\":\"完好\"}"));
        JsonNode appAfter = objectMapper.readTree(
                getJson("/api/borrow-applications/" + appId + "?viewerCampus=MAIN")
                        .getResponse().getContentAsString());
        assertEquals("RETURNED", appAfter.get("data").get("application").get("status").asText());

        // ========== 五、过期只提醒，不冻结出库与归还 ==========
        // relicB 登记一份登记时即已过期的估值 V1
        expectOk(postJson("/api/relics/" + relicB + "/insurance-valuations",
                registerBody("150000", "CNY", "专家鉴定", "文博鉴定中心",
                        "2025-01-01T00:00:00", "2026-09-30T00:00:00", "保险管理员甲")));
        // 手动触发扫描（与定时任务同口径，幂等）
        JsonNode scan = dataOf(postEmpty("/api/insurance-valuation-reminders/scan"));
        assertTrue(scan.get("touched").asInt() >= 1);

        JsonNode expired = dataOf(getJson(
                "/api/insurance-valuation-reminders?role=INSURANCE_ADMIN&status=OPEN"));
        boolean hasExpired = false;
        for (JsonNode r : expired) {
            if ("EXPIRED".equals(r.get("type").asText()) && "OPEN".equals(r.get("status").asText())) {
                hasExpired = true;
                // 定时扫描可能已重复提醒过，计数只要求至少 1
                assertTrue(r.get("raisedCount").asInt() >= 1);
            }
        }
        assertTrue(hasExpired, "应存在 OPEN 的 EXPIRED 提醒");

        // 估值已过期：借展审批、出库、归还全部不被冻结
        long appB = borrowApproveLend(relicB);
        JsonNode bindB = dataOf(getJson(
                "/api/borrow-applications/" + appB + "/insurance-valuation?role=INSURANCE_ADMIN"));
        assertTrue(bindB.get("bound").asBoolean(), "签约时仍固化当时（已过期）生效版本");
        expectOk(postJson("/api/borrow-applications/" + appB + "/return",
                "{\"returnedBy\":\"接收员丁\",\"returnCondition\":\"完好\"}"));

        // 新版本生效后旧版本过期提醒自动闭环（不删除，留痕）
        JsonNode v2b = dataOf(postJson(
                "/api/relics/" + relicB + "/insurance-valuations/revisions",
                reviseBody("160000", "CNY", "专家鉴定", "文博鉴定中心",
                        "2026-10-06T00:00:00", "2027-10-05T00:00:00",
                        "保险管理员甲", "END-2026-088")));
        expectOk(postJson("/api/insurance-valuation-versions/" + v2b.get("id").asLong() + "/review",
                "{\"reviewer\":\"财务复核乙\",\"decision\":\"APPROVE\",\"opinion\":\"同意续保估值\"}"));
        JsonNode resolved = dataOf(getJson(
                "/api/insurance-valuation-reminders?role=INSURANCE_ADMIN&status=RESOLVED"));
        boolean oldExpiredResolved = false;
        for (JsonNode r : resolved) {
            if ("EXPIRED".equals(r.get("type").asText())
                    && r.get("resolveNote").asText().contains("自动闭环")) {
                oldExpiredResolved = true;
            }
        }
        assertTrue(oldExpiredResolved, "旧版本 EXPIRED 提醒应随新版本生效 RESOLVED 留痕");

        // 复核驳回链路：再提 V3，驳回后封存只读，不能再次审批
        JsonNode v3 = dataOf(postJson(
                "/api/relics/" + relicB + "/insurance-valuations/revisions",
                reviseBody("999999", "CNY", "专家鉴定", "文博鉴定中心",
                        "2026-10-06T00:00:00", "2028-10-05T00:00:00",
                        "保险管理员甲", "END-2026-099")));
        expectOk(postJson("/api/insurance-valuation-versions/" + v3.get("id").asLong() + "/review",
                "{\"reviewer\":\"财务复核乙\",\"decision\":\"REJECT\",\"opinion\":\"金额依据不足\"}"));
        assertEquals("REJECTED", dataOf(getJson(
                "/api/relics/" + relicB + "/insurance-valuation?role=INSURANCE_ADMIN"))
                .get("versions").get(0).get("status").asText());
        expectFail(postJson("/api/insurance-valuation-versions/" + v3.get("id").asLong() + "/review",
                "{\"reviewer\":\"财务复核乙\",\"decision\":\"APPROVE\"}"),
                "不处于待复核状态");

        // ========== 六、缺权收紧：变更留痕入口拒绝，换调用地址也不放行 ==========
        expectOk(putJson("/api/policy/insurance-valuation-change",
                "{\"open\":false,\"operator\":\"馆办\",\"reason\":\"文物修复侧权限收紧演练\"}"));
        // 原修订入口拒绝
        expectFailPost("/api/relics/" + relicA + "/insurance-valuations/revisions",
                reviseBody("250000", "CNY", "市场比较法", "华瑞保险公估",
                        "2026-10-01T00:00:00", "2029-09-30T00:00:00",
                        "保险管理员甲", "END-2026-100"),
                "文物保险估值变更留痕已在入口拒绝");
        // 换调用地址：初始登记地址、双人复核地址同样拒绝（闸口在服务层，与 URL 无关）
        long relicC = createRelic("IV-C", "THREE", "MAIN");
        expectFailPost("/api/relics/" + relicC + "/insurance-valuations",
                registerBody("100000", "CNY", "市场比较法", "华瑞保险公估",
                        "2026-01-01T00:00:00", "2027-12-31T00:00:00", "保险管理员甲"),
                "文物保险估值变更留痕已在入口拒绝");
        expectFailPost("/api/insurance-valuation-versions/" + v2Id + "/review",
                "{\"reviewer\":\"其他人\",\"decision\":\"APPROVE\"}",
                "文物保险估值变更留痕已在入口拒绝");
        // 收紧不影响只读查询（字段级权限仍按角色生效）
        assertEquals(0, dataOf(getJson(
                "/api/relics/" + relicA + "/insurance-valuation?role=FINANCE"))
                .get("current").get("amount").decimalValue()
                .compareTo(new java.math.BigDecimal("230000")));
        // 放开后恢复
        expectOk(putJson("/api/policy/insurance-valuation-change",
                "{\"open\":true,\"operator\":\"馆办\",\"reason\":\"权限恢复\"}"));
        JsonNode v4 = dataOf(postJson(
                "/api/relics/" + relicA + "/insurance-valuations/revisions",
                reviseBody("235000", "CNY", "市场比较法", "华瑞保险公估",
                        "2026-10-06T00:00:00", "2029-09-30T00:00:00",
                        "保险管理员甲", "END-2026-101")));
        expectOk(postJson("/api/insurance-valuation-versions/" + v4.get("id").asLong() + "/review",
                "{\"reviewer\":\"财务复核乙\",\"decision\":\"APPROVE\",\"opinion\":\"恢复后复核通过\"}"));
        assertEquals(235000, dataOf(getJson(
                "/api/relics/" + relicA + "/insurance-valuation?role=INSURANCE_ADMIN"))
                .get("current").get("amount").decimalValue().intValue());
    }

    // ------------------------------------------------------------------
    // 业务流程辅助：三级文物、宣教场景、单角色链审批通过并出库
    // ------------------------------------------------------------------

    private long borrowApproveLend(long relicId) throws Exception {
        MvcResult apply = postJson("/api/relics/" + relicId + "/borrow-applications", "{"
                + "\"applicant\":\"馆员己\",\"borrower\":\"交流单位甲\",\"borrowScene\":\"PUBLIC_EDUCATION\","
                + "\"planLentAt\":\"2026-11-01T09:00:00\",\"expectedReturnAt\":\"2027-01-20T09:00:00\","
                + "\"purpose\":\"估值规则测试\",\"insuranceAmount\":120000}");
        long appId = dataOf(apply).get("id").asLong();
        expectOk(postJson("/api/borrow-applications/" + appId + "/approvals", "{"
                + "\"approverRole\":\"BORROW_DEPT\",\"approverName\":\"馆藏部张三\","
                + "\"decision\":\"APPROVE\",\"opinion\":\"同意\"}"));
        expectOk(postJson("/api/borrow-applications/" + appId + "/lend",
                "{\"lentBy\":\"库房交接员\"}"));
        return appId;
    }

    private String registerBody(String amount, String currency, String source, String org,
                                String from, String until, String operator) {
        return "{"
                + "\"operator\":\"" + operator + "\","
                + "\"amount\":" + amount + ","
                + "\"currency\":\"" + currency + "\","
                + "\"valuationSource\":\"" + source + "\","
                + "\"appraiserOrg\":\"" + org + "\","
                + "\"appraiserName\":\"估价师某\","
                + "\"reportNo\":\"RP-" + amount + "\","
                + "\"validFrom\":\"" + from + "\","
                + "\"validUntil\":\"" + until + "\","
                + "\"basis\":\"近三年同类成交比对\","
                + "\"vouchers\":[{\"fileName\":\"评估报告V1.pdf\",\"voucherRef\":\"ARC/V/"
                + amount + "\",\"note\":\"评估报告原件归档\"}]}";
    }

    private String reviseBody(String amount, String currency, String source, String org,
                              String from, String until, String proposer, String endorsementNo) {
        return "{"
                + "\"proposer\":\"" + proposer + "\","
                + "\"amount\":" + amount + ","
                + "\"currency\":\"" + currency + "\","
                + "\"valuationSource\":\"" + source + "\","
                + "\"appraiserOrg\":\"" + org + "\","
                + "\"appraiserName\":\"估价师某\","
                + "\"reportNo\":\"RP-NEW-" + amount + "\","
                + "\"validFrom\":\"" + from + "\","
                + "\"validUntil\":\"" + until + "\","
                + "\"basis\":\"批单修订重新评估\","
                + "\"endorsementNo\":\"" + endorsementNo + "\","
                + "\"endorsementIssuedAt\":\"2026-10-05T00:00:00\","
                + "\"endorsementNote\":\"保额调整批单\","
                + "\"vouchers\":[{\"fileName\":\"保险批单.pdf\",\"voucherRef\":\"ARC/E/"
                + endorsementNo + "\",\"note\":\"批单凭证\"}]}";
    }

    private String reviseBodyNoEndorsement() {
        return "{"
                + "\"proposer\":\"保险管理员甲\",\"amount\":230000,\"currency\":\"CNY\","
                + "\"valuationSource\":\"市场比较法\",\"appraiserOrg\":\"华瑞保险公估\","
                + "\"validFrom\":\"2026-10-01T00:00:00\",\"validUntil\":\"2028-09-30T00:00:00\","
                + "\"vouchers\":[{\"fileName\":\"x.pdf\",\"voucherRef\":\"ARC/E/X\"}]}";
    }

    private long createRelic(String no, String grade, String campus) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + no + "-青铜鼎\",\"category\":\"青铜器\","
                + "\"material\":\"青铜\",\"era\":\"西周\",\"source\":\"考古发掘\","
                + "\"preservationGrade\":\"" + grade + "\",\"campusCode\":\"" + campus + "\"}"))
                .get("id").asLong();
    }

    // ------------------------------------------------------------------

    /** 带失败信息的 postJson：失败时直接返回 MvcResult（不提前断言 200 外的 success） */
    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult putJson(String uri, String body) throws Exception {
        return mockMvc.perform(put(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean(), "期望失败，但成功了");
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际：" + root.get("message").asText());
    }

    private void expectFailGet(String uri, String messageContains) throws Exception {
        expectFail(mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn(), messageContains);
    }

    private void expectFailPost(String uri, String body, String messageContains) throws Exception {
        expectFail(mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn(), messageContains);
    }
}

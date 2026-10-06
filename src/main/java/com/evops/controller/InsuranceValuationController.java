package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.ValuationRegisterRequest;
import com.evops.dto.ValuationReviewRequest;
import com.evops.dto.ValuationReviseRequest;
import com.evops.entity.InsuranceValuation;
import com.evops.entity.InsuranceValuationReminder;
import com.evops.entity.InsuranceValuationVersion;
import com.evops.service.InsuranceValuationRemindScheduler;
import com.evops.service.InsuranceValuationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 文物保险估值管理接口（museum-15）。
 *
 * <p>估值登记/金额修订/双人复核在服务入口统一校验权限开关；
 * 查询按角色字段级脱敏：保险管理员见全部，财务只见金额/币种/有效期与凭证，
 * 公众（PUBLIC）与普通馆员（LIBRARIAN）拒绝访问。</p>
 */
@RestController
@RequestMapping("/api")
public class InsuranceValuationController {

    private final InsuranceValuationService valuationService;
    private final InsuranceValuationRemindScheduler remindScheduler;

    public InsuranceValuationController(InsuranceValuationService valuationService,
                                        InsuranceValuationRemindScheduler remindScheduler) {
        this.valuationService = valuationService;
        this.remindScheduler = remindScheduler;
    }

    /** 初始登记估值（V1 登记即生效） */
    @PostMapping("/relics/{relicId}/insurance-valuations")
    public ApiResponse<InsuranceValuation> register(
            @PathVariable Long relicId,
            @Valid @RequestBody ValuationRegisterRequest request) {
        return ApiResponse.ok(valuationService.register(relicId, request));
    }

    /** 金额修订：生成新版本（PENDING_APPROVAL）并关联保险批单 */
    @PostMapping("/relics/{relicId}/insurance-valuations/revisions")
    public ApiResponse<InsuranceValuationVersion> revise(
            @PathVariable Long relicId,
            @Valid @RequestBody ValuationReviseRequest request) {
        return ApiResponse.ok(valuationService.revise(relicId, request));
    }

    /** 双人审批金额修订：复核人必须与提出人不同 */
    @PostMapping("/insurance-valuation-versions/{versionId}/review")
    public ApiResponse<InsuranceValuationVersion> review(
            @PathVariable Long versionId,
            @Valid @RequestBody ValuationReviewRequest request) {
        return ApiResponse.ok(valuationService.review(versionId, request));
    }

    /**
     * 估值详情（版本链/当前版本/未闭环提醒），按角色脱敏。
     * role=INSURANCE_ADMIN 全量 / FINANCE 仅金额、币种、有效期与凭证；
     * PUBLIC、LIBRARIAN 或未声明角色一律拒绝。
     */
    @GetMapping("/relics/{relicId}/insurance-valuation")
    public ApiResponse<Map<String, Object>> view(
            @PathVariable Long relicId,
            @RequestParam(value = "role", required = false) String role) {
        return ApiResponse.ok(valuationService.viewByRelic(relicId, role));
    }

    /**
     * 借展沿用的签约时估值口径：返回绑定版本而非当前版本。
     * 借展期间即使估值修订/过期，出库与归还原口径仍以该绑定版本为准。
     */
    @GetMapping("/borrow-applications/{applicationId}/insurance-valuation")
    public ApiResponse<Map<String, Object>> loanBinding(
            @PathVariable Long applicationId,
            @RequestParam(value = "role", required = false) String role) {
        return ApiResponse.ok(valuationService.viewLoanBinding(applicationId, role));
    }

    /** 临期/过期提醒列表（仅保险管理员/财务可查） */
    @GetMapping("/insurance-valuation-reminders")
    public ApiResponse<List<InsuranceValuationReminder>> reminders(
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(valuationService.listReminders(role, status));
    }

    /** 手动触发一轮有效期提醒扫描（定时任务同口径；提醒不冻结出库/归还） */
    @PostMapping("/insurance-valuation-reminders/scan")
    public ApiResponse<Map<String, Object>> scanReminders() {
        int touched = remindScheduler.scanOnce();
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("touched", touched);
        data.put("note", "提醒仅提示，不冻结借展出库与归还");
        return ApiResponse.ok(data);
    }
}

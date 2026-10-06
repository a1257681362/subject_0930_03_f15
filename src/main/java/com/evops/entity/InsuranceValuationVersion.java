package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 保险估值版本（museum-15）：只追加不改写。
 *
 * <p>V1 随初始登记直接 APPROVED；此后每次金额修订生成新版本（PENDING_APPROVAL），
 * 必须由与提出人不同的复核人双人审批通过后才生效，生效时旧 APPROVED 版本置
 * SUPERSEDED。修订版本必须登记保险批单号（{@code endorsementNo}）作为变更关联。
 * 来源/评估机构/币种/有效期/依据均在版本上固化，借展签约时按版本绑定。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_insurance_valuation_version")
public class InsuranceValuationVersion extends BaseEntity {

    private Long valuationId;

    /** 版本号，估值单内从 1 递增 */
    private Integer versionNo;

    /** PENDING_APPROVAL / APPROVED / SUPERSEDED / REJECTED */
    private String status;

    private BigDecimal amount;

    /** 币种 ISO 码，默认 CNY */
    private String currency;

    /** 估值来源：如市场比较、专家鉴定、购置凭证 */
    private String valuationSource;

    /** 评估机构 */
    private String appraiserOrg;

    /** 评估师/经办人 */
    private String appraiserName;

    /** 评估报告编号 */
    private String reportNo;

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

    /** 估值依据说明 */
    private String basis;

    /** 关联保险批单号：修订版本必填，初始登记可空 */
    private String endorsementNo;

    private LocalDateTime endorsementIssuedAt;

    private String endorsementNote;

    /** 金额修订提出人（V1 登记人） */
    private String proposerName;

    private LocalDateTime proposedAt;

    /** 双人审批复核人，必须与提出人不同 */
    private String reviewerName;

    private LocalDateTime reviewedAt;

    private String reviewOpinion;

    /** 被新版本取代的时间（旧 APPROVED -> SUPERSEDED） */
    private LocalDateTime supersededAt;
}

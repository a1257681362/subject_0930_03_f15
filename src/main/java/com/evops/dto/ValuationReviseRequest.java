package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 保险估值金额修订（museum-15）。
 *
 * <p>修订必须生成新版本并关联保险批单（批单号必填），新版本先进入
 * PENDING_APPROVAL，由与提出人不同的复核人审批通过后才生效。
 * 来源/评估机构/币种/有效期随新版本一并固化。</p>
 */
@Data
public class ValuationReviseRequest {

    /** 修订提出人（保险管理员），不能同时是复核人 */
    @NotBlank(message = "修订提出人不能为空")
    private String proposer;

    @NotNull(message = "修订后金额不能为空")
    @DecimalMin(value = "0", message = "估值金额不能为负")
    private BigDecimal amount;

    private String currency;

    @NotBlank(message = "估值来源不能为空")
    private String valuationSource;

    @NotBlank(message = "评估机构不能为空")
    private String appraiserOrg;

    private String appraiserName;

    private String reportNo;

    @NotNull(message = "估值生效时间不能为空")
    private LocalDateTime validFrom;

    @NotNull(message = "估值有效期截止时间不能为空")
    private LocalDateTime validUntil;

    private String basis;

    /** 关联保险批单号：金额修订必须关联批单 */
    @NotBlank(message = "金额修订必须关联保险批单号")
    private String endorsementNo;

    /** 批单签发时间 */
    private LocalDateTime endorsementIssuedAt;

    /** 批单备注（变更原因等） */
    private String endorsementNote;

    /** 本次修订的凭证（批单/新评估报告，至少一张） */
    @NotEmpty(message = "修订凭证至少登记一张")
    @Valid
    private List<ValuationRegisterRequest.VoucherItem> vouchers;
}

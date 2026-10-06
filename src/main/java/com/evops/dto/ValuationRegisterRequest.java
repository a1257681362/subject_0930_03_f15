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
 * 文物保险估值初始登记（museum-15）。
 *
 * <p>一件文物只能登记一份估值；来源/评估机构/币种/有效期随 V1 版本固化，
 * V1 登记即生效（登记人对首次估值负责，无需双人审批；金额修订才须双人审批）。</p>
 */
@Data
public class ValuationRegisterRequest {

    /** 登记人（保险管理员） */
    @NotBlank(message = "登记人不能为空")
    private String operator;

    @NotNull(message = "估值金额不能为空")
    @DecimalMin(value = "0", message = "估值金额不能为负")
    private BigDecimal amount;

    /** 币种 ISO 码，缺省 CNY */
    private String currency;

    /** 估值来源：市场比较/专家鉴定/购置凭证等 */
    @NotBlank(message = "估值来源不能为空")
    private String valuationSource;

    @NotBlank(message = "评估机构不能为空")
    private String appraiserOrg;

    /** 评估师/经办人 */
    private String appraiserName;

    /** 评估报告编号 */
    private String reportNo;

    @NotNull(message = "估值生效时间不能为空")
    private LocalDateTime validFrom;

    @NotNull(message = "估值有效期截止时间不能为空")
    private LocalDateTime validUntil;

    /** 估值依据说明 */
    private String basis;

    /** 凭证（至少一张评估报告/凭证引用） */
    @NotEmpty(message = "估值凭证至少登记一张")
    @Valid
    private List<VoucherItem> vouchers;

    /** 估值凭证项：只存引用，不落原件内容 */
    @Data
    public static class VoucherItem {

        @NotBlank(message = "凭证文件名不能为空")
        private String fileName;

        @NotBlank(message = "凭证引用不能为空")
        private String voucherRef;

        private String note;
    }
}

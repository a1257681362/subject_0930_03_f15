package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 估值凭证（museum-15）：评估报告/批单等凭证引用，挂在估值版本上只追加。
 *
 * <p>字段级访问：保险管理员可见全部；财务只见金额/币种/有效期与凭证；
 * 公众与普通馆员不可见。这里只存引用（voucher_ref），不落敏感原件内容。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_insurance_valuation_voucher")
public class InsuranceValuationVoucher extends BaseEntity {

    private Long versionId;

    private String fileName;

    /** 凭证引用（档案柜编号或对象存储键） */
    private String voucherRef;

    private String note;

    private String uploadedBy;

    private LocalDateTime uploadedAt;
}

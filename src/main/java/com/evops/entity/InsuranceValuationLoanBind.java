package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 借展估值绑定（museum-15）：借阅审批全部通过（签约生成借阅快照）时，
 * 把当时生效的估值版本固化绑定，一件借阅申请一行、不可变。
 *
 * <p>借展期间即使估值被修订出新版本或原版本过期，出库、归还口径一律沿用
 * 绑定版本（签约时快照），不随后续变更改写。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_insurance_valuation_loan_bind")
public class InsuranceValuationLoanBind extends BaseEntity {

    private Long valuationId;

    /** 签约时固化的估值版本 id */
    private Long valuationVersionId;

    /** 借阅申请 id（全库唯一：一件申请只绑定一次） */
    private Long applicationId;

    /** 关联借阅快照 id */
    private Long snapshotId;

    private Long relicId;

    private LocalDateTime boundAt;
}

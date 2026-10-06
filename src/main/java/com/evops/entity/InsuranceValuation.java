package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 文物保险估值主单（museum-15）：一件文物全库唯一一份。
 *
 * <p>估值来源、评估机构、币种、有效期均随{@link InsuranceValuationVersion 版本}固化；
 * 当前生效版本由 {@code currentVersionId} 指向。金额修订只追加新版本并关联保险批单，
 * 旧生效版本置 SUPERSEDED，永不改写。主单本身不撤销、不物理删除。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_insurance_valuation")
public class InsuranceValuation extends BaseEntity {

    /** 估值单号 IV-000001 */
    private String valuationNo;

    private Long relicId;

    /** ACTIVE：估值只追加版本，无作废态 */
    private String status;

    /** 当前生效版本 id（初始登记 V1 生效后写入） */
    private Long currentVersionId;

    private String registeredBy;

    private LocalDateTime registeredAt;
}

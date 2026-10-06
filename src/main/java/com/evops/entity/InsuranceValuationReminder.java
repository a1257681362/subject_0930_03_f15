package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 估值有效期提醒（museum-15）：临期 EXPIRING / 过期 EXPIRED。
 *
 * <p>按 (valuation_id, version_id, type) 幂等只追加，重复扫描只累加 raised_count
 * 与 last_raised_at。新版本生效后旧版本提醒自动闭环（RESOLVED）。
 * <b>提醒仅提示，不冻结借展出库，更不冻结归还</b>。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_insurance_valuation_reminder")
public class InsuranceValuationReminder extends BaseEntity {

    /** 提醒单号 IR-000001 */
    private String remindNo;

    private Long valuationId;

    private Long versionId;

    /** EXPIRING 临期 / EXPIRED 已过期 */
    private String type;

    /** OPEN 待处理 / RESOLVED 已闭环 */
    private String status;

    private LocalDateTime firstRaisedAt;

    private LocalDateTime lastRaisedAt;

    private Integer raisedCount;

    private String resolvedBy;

    private LocalDateTime resolvedAt;

    private String resolveNote;
}

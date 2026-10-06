package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 保险估值金额修订双人审批（museum-15）。
 *
 * <p>复核人必须与修订提出人不同（同人复核一律拒绝）；APPROVE 后新版本生效、
 * 旧生效版本置 SUPERSEDED；REJECT 必须填写意见，驳回版本封存只读。</p>
 */
@Data
public class ValuationReviewRequest {

    @NotBlank(message = "复核人不能为空")
    private String reviewer;

    /**
     * APPROVE 通过 / REJECT 驳回；
     * 数据库版本状态 APPROVED/REJECTED 由服务层映射，避免码值歧义。
     */
    @NotNull(message = "复核决定不能为空")
    private String decision;

    /** 复核意见（驳回必填） */
    private String opinion;
}

package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 文物保险估值变更留痕权限开关调整（museum-15）。
 * open=false 表示博物馆文物修复侧缺权收紧：估值变更动作在服务入口即拒绝；
 * 已固化版本、批单关联、借展绑定与台账事实不受影响。
 */
@Data
public class ValuationPolicyUpdateRequest {

    /** true 放开估值变更 / false 收紧（入口拒绝） */
    @NotNull(message = "开放状态不能为空")
    private Boolean open;

    /** 操作人，留痕用 */
    private String operator;

    /** 调整原因 */
    private String reason;
}

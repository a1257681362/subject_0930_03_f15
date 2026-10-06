package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.ValuationPolicyUpdateRequest;
import com.evops.entity.PermissionPolicy;
import com.evops.service.LedgerService;
import com.evops.service.PermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;

import static com.evops.constant.MuseumConstants.ACTION_IVAL_POLICY_RELAX;
import static com.evops.constant.MuseumConstants.ACTION_IVAL_POLICY_TIGHTEN;

/**
 * 文物保险估值变更留痕权限开关（museum-15）。
 *
 * <p>收紧（open=false）模拟博物馆文物修复用户缺权：估值登记/金额修订/复核等
 * 推进型动作在服务入口即拒绝，换任何调用地址都不放行；已固化估值版本、
 * 保险批单关联、借展绑定与台账事实不因收紧改动或删除。</p>
 */
@RestController
@RequestMapping("/api/policy/insurance-valuation-change")
public class ValuationPolicyController {

    /** 台账中全局策略事件使用的占位文物 id（无具体文物归属） */
    private static final long GLOBAL_SCOPE = 0L;

    private final PermissionService permissionService;
    private final LedgerService ledgerService;

    public ValuationPolicyController(PermissionService permissionService,
                                     LedgerService ledgerService) {
        this.permissionService = permissionService;
        this.ledgerService = ledgerService;
    }

    /** 查看当前策略 */
    @GetMapping
    public ApiResponse<PermissionPolicy> get() {
        return ApiResponse.ok(permissionService.getPolicy());
    }

    /** 收紧（open=false，入口拒绝）/ 放开（open=true）保险估值变更留痕，并写台账 */
    @PutMapping
    public ApiResponse<PermissionPolicy> update(@Valid @RequestBody ValuationPolicyUpdateRequest request) {
        boolean wasOpen = permissionService.isValuationChangeOpen();
        PermissionPolicy policy = permissionService.updateValuationChangeOpen(
                request.getOpen(), request.getOperator(), request.getReason());

        if (wasOpen != request.getOpen()) {
            String operator = request.getOperator() == null ? "SYSTEM" : request.getOperator();
            String summary = (request.getOpen()
                    ? "文物保险估值变更留痕权限放开"
                    : "博物馆文物修复用户缺权，文物保险估值变更留痕在入口拒绝，"
                            + "已固化版本、保险批单关联与借展绑定继续保留")
                    + "，操作人：" + operator
                    + (request.getReason() != null && !request.getReason().isEmpty()
                            ? "，原因：" + request.getReason() : "");
            ledgerService.append(GLOBAL_SCOPE, null, null,
                    request.getOpen() ? ACTION_IVAL_POLICY_RELAX : ACTION_IVAL_POLICY_TIGHTEN,
                    "POLICY#1", summary, LocalDateTime.now());
        }
        return ApiResponse.ok(policy);
    }
}

package com.evops.service;

import com.evops.common.PermissionClosedException;
import com.evops.entity.PermissionPolicy;
import com.evops.mapper.PermissionPolicyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.evops.constant.MuseumConstants.POLICY_ID;

/**
 * 文物修复权限策略：单行开关。
 *
 * <p>当博物馆文物修复权限被收紧（{@code repairPhotoUploadOpen=false}）时，
 * 影像上传等推进型动作立即停止；放开后恢复。策略翻转不回滚、不删除任何
 * 已确认的影像版本、物理文件或台账事实。</p>
 *
 * <p>当文物修复用户缺权（{@code insuranceValuationChangeOpen=false}）时，
 * 「文物保险估值变更留痕」相关推进型动作（估值登记/金额修订/复核/凭证登记）
 * 在服务入口即被拒绝。闸口位于服务层而非控制器或 URL 过滤链，
 * 因而<b>更换调用地址、绕过原入口同样不放行</b>；
 * 已固化估值版本、保险批单关联、借展绑定与台账事实继续保留。</p>
 */
@Service
public class PermissionService {

    private final PermissionPolicyMapper policyMapper;

    public PermissionService(PermissionPolicyMapper policyMapper) {
        this.policyMapper = policyMapper;
    }

    /** 当前是否允许影像上传 */
    public boolean isUploadOpen() {
        return getPolicy().getRepairPhotoUploadOpen();
    }

    /** 收紧时直接拒绝，上传不推进任何写入 */
    public void requireUploadOpen() {
        if (!isUploadOpen()) {
            throw new PermissionClosedException(
                    "博物馆文物修复权限已收紧，影像上传已停止推进；已确认的版本归档继续保留");
        }
    }

    /** 当前是否允许文物保险估值变更留痕 */
    public boolean isValuationChangeOpen() {
        return getPolicy().getInsuranceValuationChangeOpen();
    }

    /**
     * 文物修复用户缺权收紧时，保险估值变更留痕在入口直接拒绝。
     * 闸口在服务层：无论从哪个 URL / 调用地址进入，都绕不过本检查。
     */
    public void requireValuationChangeOpen() {
        if (!isValuationChangeOpen()) {
            throw new PermissionClosedException(
                    "博物馆文物修复用户缺权，文物保险估值变更留痕已在入口拒绝；"
                            + "已固化估值版本、保险批单关联与借展绑定继续保留");
        }
    }

    public PermissionPolicy getPolicy() {
        PermissionPolicy policy = policyMapper.selectById(POLICY_ID);
        if (policy == null) {
            // 理论上 schema.sql 已播种 id=1；兜底补一条开放策略，避免空指针
            policy = new PermissionPolicy();
            policy.setId(POLICY_ID);
            policy.setRepairPhotoUploadOpen(true);
            policy.setInsuranceValuationChangeOpen(true);
            policy.setUpdatedBy("SYSTEM");
            policy.setUpdatedAt(LocalDateTime.now());
            policyMapper.insert(policy);
        }
        // 既有文件库升级：ALTER TABLE 补列后历史单行可能映射为 null，按开放兜底
        if (policy.getInsuranceValuationChangeOpen() == null) {
            policy.setInsuranceValuationChangeOpen(true);
        }
        return policy;
    }

    /** 收紧 / 放开影像上传，返回最新策略 */
    @Transactional
    public PermissionPolicy updateOpen(boolean open, String operator, String reason) {
        PermissionPolicy policy = getPolicy();
        boolean changed = !Boolean.valueOf(open).equals(policy.getRepairPhotoUploadOpen());
        policy.setRepairPhotoUploadOpen(open);
        policy.setUpdatedBy(operator == null || operator.isEmpty() ? "SYSTEM" : operator);
        policy.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(policy);
        // reason 仅用于调用方留痕/日志，不另建历史表
        if (changed) {
            org.slf4j.LoggerFactory.getLogger(PermissionService.class)
                    .info("文物修复影像上传权限已{}，操作人={}，原因={}",
                            open ? "放开" : "收紧", policy.getUpdatedBy(), reason);
        }
        return policy;
    }

    /** 收紧 / 放开文物保险估值变更留痕，返回最新策略 */
    @Transactional
    public PermissionPolicy updateValuationChangeOpen(boolean open, String operator, String reason) {
        PermissionPolicy policy = getPolicy();
        boolean changed = !Boolean.valueOf(open).equals(policy.getInsuranceValuationChangeOpen());
        policy.setInsuranceValuationChangeOpen(open);
        policy.setUpdatedBy(operator == null || operator.isEmpty() ? "SYSTEM" : operator);
        policy.setUpdatedAt(LocalDateTime.now());
        policyMapper.updateById(policy);
        if (changed) {
            org.slf4j.LoggerFactory.getLogger(PermissionService.class)
                    .info("文物保险估值变更留痕权限已{}，操作人={}，原因={}",
                            open ? "放开" : "收紧（入口拒绝）", policy.getUpdatedBy(), reason);
        }
        return policy;
    }
}

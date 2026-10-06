package com.evops.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限策略（单行 id=1）：文物修复相关操作的收紧开关。
 *
 * <p>{@code repairPhotoUploadOpen=false} 表示博物馆文物修复权限被收紧，
 * 影像上传立即停止推进；已确认的影像版本、物理文件与台账事实不受影响、全部保留。</p>
 *
 * <p>{@code insuranceValuationChangeOpen=false} 表示文物修复用户缺权，
 * 「文物保险估值变更留痕」在服务入口即拒绝（与调用地址无关，换 URL 也不放行）；
 * 已固化估值版本、保险批单关联、借展绑定与台账事实全部保留。</p>
 */
@Data
@TableName("t_permission_policy")
public class PermissionPolicy {

    @TableId(type = IdType.INPUT)
    private Integer id;

    /** 影像上传是否开放：true 开放 / false 已收紧 */
    private Boolean repairPhotoUploadOpen;

    /** 文物保险估值变更留痕是否开放：true 开放 / false 缺权收紧、入口拒绝 */
    private Boolean insuranceValuationChangeOpen;

    private String updatedBy;

    private LocalDateTime updatedAt;
}

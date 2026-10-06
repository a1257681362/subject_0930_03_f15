package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文物保险估值管理配置（museum.insurance.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.insurance")
public class MuseumInsuranceProperties {

    /** 临期提醒提前量（天）：有效期截止前该天数内提出 EXPIRING 提醒 */
    private int expiryRemindDays = 30;

    /** 临期/过期提醒扫描间隔（毫秒） */
    private long remindScanIntervalMs = 60_000L;

    /** 应用启动后是否立即补扫一次 */
    private boolean remindScanOnStartup = true;
}

package com.evops.service;

import com.evops.config.MuseumInsuranceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 保险估值有效期提醒扫描（museum-15）。
 *
 * <p>周期扫描当前生效估值版本：进入临期窗口提出 EXPIRING、已过期提出 EXPIRED，
 * 按估值+版本+类型幂等。应用就绪后先补扫一次（重启场景）。</p>
 *
 * <p><b>提醒只提示、不改任何业务状态：绝不冻结借展出库，更不冻结归还。</b></p>
 */
@Component
public class InsuranceValuationRemindScheduler {

    private static final Logger log = LoggerFactory.getLogger(InsuranceValuationRemindScheduler.class);

    private final InsuranceValuationService valuationService;
    private final MuseumInsuranceProperties properties;
    private final AtomicBoolean startedUp = new AtomicBoolean(false);

    public InsuranceValuationRemindScheduler(InsuranceValuationService valuationService,
                                             MuseumInsuranceProperties properties) {
        this.valuationService = valuationService;
        this.properties = properties;
    }

    /** 启动就绪补扫：重启后立即补提停机期间到期的提醒 */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        if (properties.isRemindScanOnStartup() && startedUp.compareAndSet(false, true)) {
            log.info("保险估值有效期提醒：启动补扫临期/过期估值");
            scanOnce();
        }
    }

    /** 周期扫描（fixedDelay：上一轮结束后再计时） */
    @Scheduled(fixedDelayString = "${museum.insurance.remind-scan-interval-ms:60000}")
    public void scanOnSchedule() {
        scanOnce();
    }

    /**
     * 执行一轮扫描，返回本轮触达的提醒数（新建或重复提醒）。公开供接口/测试手动触发。
     * 提醒不冻结借展与归还。
     */
    public int scanOnce() {
        LocalDateTime now = LocalDateTime.now();
        int touched = valuationService.scanReminders(now, properties.getExpiryRemindDays());
        if (touched > 0) {
            log.info("保险估值有效期提醒扫描：触达 {} 条临期/过期提醒（仅提醒，不冻结出库与归还）", touched);
        }
        return touched;
    }
}

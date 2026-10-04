package com.example.library.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定时批处理：取书超时释放、逾期罚款累计、到期提醒。
 * 可通过 app.scheduling.enabled=false 关闭（测试中手动触发对应服务方法）。
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class LibraryScheduler {

    private static final Logger log = LoggerFactory.getLogger(LibraryScheduler.class);

    private final HoldService holdService;
    private final LoanService loanService;
    private final AppClock clock;

    public LibraryScheduler(HoldService holdService, LoanService loanService, AppClock clock) {
        this.holdService = holdService;
        this.loanService = loanService;
        this.clock = clock;
    }

    /** 每小时：到馆未取超时释放给下一位 */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 5_000L)
    @Transactional
    public void expireHolds() {
        int n = holdService.expireOverdueHolds();
        if (n > 0) {
            log.info("取书超时检查：处理 {} 条预约", n);
        }
    }

    /** 每天 02:00：逾期罚款刷新 + 到期前 1 天提醒（声称已还争议中的借阅暂停） */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void dailyOverdue() {
        int n = loanService.runDailyOverdueJob();
        if (n > 0) {
            log.info("逾期检查：处理 {} 条借阅", n);
        }
    }
}

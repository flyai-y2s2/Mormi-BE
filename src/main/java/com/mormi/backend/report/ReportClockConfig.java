package com.mormi.backend.report;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ReportClockConfig {

    @Bean
    Clock reportClock() {
        return Clock.system(WeeklyReportPeriod.REPORT_ZONE);
    }
}

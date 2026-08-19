package com.mormi.backend.report;

import com.mormi.backend.common.ApiException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

record WeeklyReportPeriod(
        LocalDate weekStart,
        LocalDate weekEnd,
        LocalDate earliestWeekStart,
        LocalDate latestWeekStart,
        OffsetDateTime startInclusive,
        OffsetDateTime endExclusive) {

    static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Seoul");

    static WeeklyReportPeriod resolve(LocalDate requested, OffsetDateTime joinedAt, Clock clock) {
        LocalDate latest = LocalDate.now(clock.withZone(REPORT_ZONE))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate earliest = joinedAt.atZoneSameInstant(REPORT_ZONE).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate selected = requested == null ? latest : requested;
        if (selected.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw ApiException.badRequest("week_start", "주 시작일은 월요일이어야 합니다.");
        }
        if (selected.isAfter(latest)) {
            throw ApiException.badRequest("week_start", "미래 주차는 조회할 수 없습니다.");
        }
        if (selected.isBefore(earliest)) {
            throw ApiException.badRequest("week_start", "가입 이전 주차는 조회할 수 없습니다.");
        }
        return new WeeklyReportPeriod(
                selected,
                selected.plusDays(6),
                earliest,
                latest,
                selected.atStartOfDay(REPORT_ZONE).toOffsetDateTime(),
                selected.plusWeeks(1).atStartOfDay(REPORT_ZONE).toOffsetDateTime());
    }
}

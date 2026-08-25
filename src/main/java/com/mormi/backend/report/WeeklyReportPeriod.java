package com.mormi.backend.report;

import com.mormi.backend.common.ApiException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

record WeeklyReportPeriod(
        LocalDate weekStart,
        LocalDate weekEnd,
        LocalDate earliestWeekStart,
        LocalDate latestWeekStart,
        List<LocalDate> availableWeekStarts,
        OffsetDateTime startInclusive,
        OffsetDateTime endExclusive) {

    static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Seoul");

    static WeeklyReportPeriod resolve(LocalDate requested, OffsetDateTime joinedAt, Clock clock) {
        return resolve(requested, joinedAt, clock, List.of());
    }

    static WeeklyReportPeriod resolve(
            LocalDate requested,
            OffsetDateTime joinedAt,
            Clock clock,
            List<LocalDate> availableWeekStarts) {
        LocalDate latest = LocalDate.now(clock.withZone(REPORT_ZONE))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate joinedWeek = joinedAt.atZoneSameInstant(REPORT_ZONE).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<LocalDate> available = availableWeekStarts == null
                ? List.of()
                : availableWeekStarts.stream()
                        .filter(week -> week != null && !week.isAfter(latest))
                        .distinct()
                        .sorted()
                        .toList();
        LocalDate earliest = available.isEmpty() ? joinedWeek : available.getFirst();
        LocalDate latestAvailable = available.isEmpty() ? latest : available.getLast();
        LocalDate selected = requested == null ? latestAvailable : requested;
        if (selected.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw ApiException.badRequest("week_start", "주 시작일은 월요일이어야 합니다.");
        }
        if (selected.isAfter(latest)) {
            throw ApiException.badRequest("week_start", "미래 주차는 조회할 수 없습니다.");
        }
        if (selected.isBefore(joinedWeek) && !available.contains(selected)) {
            throw ApiException.badRequest("week_start", "가입 이전 주차는 조회할 수 없습니다.");
        }
        return new WeeklyReportPeriod(
                selected,
                selected.plusDays(6),
                earliest,
                latestAvailable,
                available,
                selected.atStartOfDay(REPORT_ZONE).toOffsetDateTime(),
                selected.plusWeeks(1).atStartOfDay(REPORT_ZONE).toOffsetDateTime());
    }
}

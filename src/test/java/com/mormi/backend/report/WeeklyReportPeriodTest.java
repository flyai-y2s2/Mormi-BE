package com.mormi.backend.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mormi.backend.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeeklyReportPeriodTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-19T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Test
    void defaultsToCurrentSeoulMondayAndUsesHalfOpenBoundary() {
        WeeklyReportPeriod period = WeeklyReportPeriod.resolve(
                null, OffsetDateTime.parse("2026-08-03T09:00:00+09:00"), CLOCK, List.of());

        assertThat(period.weekStart()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(period.weekEnd()).isEqualTo(LocalDate.parse("2026-08-23"));
        assertThat(period.startInclusive()).isEqualTo(OffsetDateTime.parse("2026-08-17T00:00:00+09:00"));
        assertThat(period.endExclusive()).isEqualTo(OffsetDateTime.parse("2026-08-24T00:00:00+09:00"));
    }

    @Test
    void defaultsToLatestWeekWithDataAndExposesOnlyAvailableWeeks() {
        WeeklyReportPeriod period = WeeklyReportPeriod.resolve(
                null,
                OffsetDateTime.parse("2026-08-03T09:00:00+09:00"),
                CLOCK,
                List.of(
                        LocalDate.parse("2026-08-03"),
                        LocalDate.parse("2026-08-17"),
                        LocalDate.parse("2026-08-03")));

        assertThat(period.weekStart()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(period.earliestWeekStart()).isEqualTo(LocalDate.parse("2026-08-03"));
        assertThat(period.latestWeekStart()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(period.availableWeekStarts()).containsExactly(
                LocalDate.parse("2026-08-03"),
                LocalDate.parse("2026-08-17"));
    }

    @Test
    void rejectsNonMondayFutureAndPreSignupWeeks() {
        OffsetDateTime joined = OffsetDateTime.parse("2026-08-05T09:00:00+09:00");
        assertThatThrownBy(() -> WeeklyReportPeriod.resolve(LocalDate.parse("2026-08-18"), joined, CLOCK, List.of()))
                .isInstanceOf(ApiException.class).hasMessageContaining("월요일");
        assertThatThrownBy(() -> WeeklyReportPeriod.resolve(LocalDate.parse("2026-08-24"), joined, CLOCK, List.of()))
                .isInstanceOf(ApiException.class).hasMessageContaining("미래");
        assertThatThrownBy(() -> WeeklyReportPeriod.resolve(LocalDate.parse("2026-07-27"), joined, CLOCK, List.of()))
                .isInstanceOf(ApiException.class).hasMessageContaining("가입 이전");
    }
}

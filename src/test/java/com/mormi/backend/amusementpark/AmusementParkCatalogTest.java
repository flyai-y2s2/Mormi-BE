package com.mormi.backend.amusementpark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mormi.backend.curriculum.AmusementParkCatalog;
import com.mormi.backend.curriculum.AmusementParkCatalog.Transfer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AmusementParkCatalogTest {

    /** 판정식 검증용 고정 문제. 출제가 랜덤이어도 계산 규칙은 이 값으로 못 박아 본다. */
    private static Map<String, Integer> facts(
            int ticketPrice, int partyCount, int snackTotal, int payerCount, int single, int dayPass) {
        Map<String, Integer> facts = new LinkedHashMap<>();
        facts.put("ticket_price", ticketPrice);
        facts.put("party_count", partyCount);
        facts.put("snack_total", snackTotal);
        facts.put("payer_count", payerCount);
        facts.put("single_ride_price", single);
        facts.put("day_pass_price", dayPass);
        return facts;
    }

    private static Map<String, Integer> sampleFacts() {
        return facts(3000, 2, 6000, 3, 2000, 10000);
    }

    @Test
    void 스테이지_순서는_이슈_계약과_같다() {
        assertThat(AmusementParkCatalog.stageOrder())
                .containsExactly("ticket", "snack_split", "pass_break_even");
    }

    @Test
    void 각_스테이지가_요구하는_검증_사실_키는_이슈_표와_같다() {
        assertThat(AmusementParkCatalog.stage("ticket").requiredVerifiedFactKeys())
                .containsExactly("ticket_price", "party_count", "total_price");
        assertThat(AmusementParkCatalog.stage("snack_split").requiredVerifiedFactKeys())
                .containsExactly("snack_total", "payer_count", "per_person");
        assertThat(AmusementParkCatalog.stage("pass_break_even").requiredVerifiedFactKeys())
                .containsExactly(
                        "single_ride_price", "day_pass_price", "break_even_rides", "benefit_from_rides");
    }

    @Test
    void 정답은_방문에_고정된_값으로만_계산한다() {
        Map<String, Integer> facts = sampleFacts();

        assertThat(AmusementParkCatalog.expectedAnswers("ticket", facts))
                .containsExactly(Map.entry("total_price", 6000));
        assertThat(AmusementParkCatalog.expectedAnswers("snack_split", facts))
                .containsExactly(Map.entry("per_person", 2000));
        // 2,000원 × 5회 = 10,000원이 본전, 6회부터 자유이용권이 이득이다.
        assertThat(AmusementParkCatalog.expectedAnswers("pass_break_even", facts))
                .containsExactly(
                        Map.entry("break_even_rides", 5), Map.entry("benefit_from_rides", 6));
    }

    @Test
    void 방문_숫자가_바뀌면_정답도_그_값을_따라간다() {
        Map<String, Integer> facts = sampleFacts();
        facts.put("ticket_price", 3500);
        facts.put("party_count", 4);

        assertThat(AmusementParkCatalog.expectedAnswers("ticket", facts))
                .containsEntry("total_price", 14000);
    }

    @Test
    void verified_facts_는_주어진_값과_정답을_함께_담는다() {
        assertThat(AmusementParkCatalog.verifiedFacts("ticket", sampleFacts()))
                .containsExactly(
                        Map.entry("ticket_price", 3000),
                        Map.entry("party_count", 2),
                        Map.entry("total_price", 6000));
    }

    @Test
    void 나누어떨어지지_않는_숫자는_출제_자체를_막는다() {
        Map<String, Integer> facts = sampleFacts();
        facts.put("payer_count", 7);  // 6,000 ÷ 7 은 딱 떨어지지 않는다

        assertThatThrownBy(() -> AmusementParkCatalog.expectedAnswers("snack_split", facts))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 방문마다_뽑은_숫자는_늘_생활수학_범위와_제약을_지킨다() {
        Random random = new Random(20260822L);  // 시드를 고정해 실패를 재현할 수 있게 한다

        for (int i = 0; i < 500; i++) {
            Map<String, Integer> facts = AmusementParkCatalog.initialFacts(random);

            assertThat(facts.keySet()).containsExactly(
                    "ticket_price", "party_count",
                    "snack_total", "payer_count",
                    "single_ride_price", "day_pass_price");
            // 금액은 천 원 단위, 인원은 2~5명
            assertThat(facts.get("ticket_price")).isBetween(2000, 5000).isEqualTo(
                    facts.get("ticket_price") / 1000 * 1000);
            assertThat(facts.get("party_count")).isBetween(2, 5);
            assertThat(facts.get("payer_count")).isBetween(2, 5);
            // 나누어떨어짐과 정수 본전이 구조적으로 보장된다
            assertThat(facts.get("snack_total") % facts.get("payer_count")).isZero();
            assertThat(facts.get("day_pass_price") % facts.get("single_ride_price")).isZero();

            // 세 단계 모두 판정이 통과해야 출제가 성립한다
            for (String stageId : AmusementParkCatalog.stageOrder()) {
                assertThat(AmusementParkCatalog.expectedAnswers(stageId, facts)).isNotEmpty();
            }
        }
    }

    @Test
    void 방문마다_문제_숫자가_달라진다() {
        Random random = new Random(7L);

        long distinct = java.util.stream.Stream.generate(() -> AmusementParkCatalog.initialFacts(random))
                .limit(50)
                .distinct()
                .count();

        assertThat(distinct).isGreaterThan(1);
    }

    @Test
    void 전이_문장은_뽑힌_문제와_다른_숫자를_쓴다() {
        Map<String, Integer> facts = sampleFacts();

        Transfer ticket = AmusementParkCatalog.transfer("ticket", facts);
        assertThat(ticket.prompt()).isEqualTo("그럼 1인 4,000원이고 3명이면?");
        assertThat(ticket.equation()).isEqualTo("4,000 × 3 = 12,000");
        assertThat(ticket.conclusion()).isEqualTo("4,000원을 3번 더한 것과 같으니까 12,000원이야!");

        // 간식: 1인당 2,000 → 3,000원, 3명 → 4명이라 12,000원짜리 새 문제가 된다.
        Transfer snack = AmusementParkCatalog.transfer("snack_split", facts);
        assertThat(snack.equation()).isEqualTo("12,000 ÷ 4 = 3,000");

        // 자유이용권: 1회 2,000 → 3,000원, 본전 5회 → 6회라 18,000원짜리 새 문제가 된다.
        Transfer pass = AmusementParkCatalog.transfer("pass_break_even", facts);
        assertThat(pass.equation()).isEqualTo("18,000 ÷ 3,000 = 6");
        assertThat(pass.conclusion()).isEqualTo("6번 타면 본전이고, 7번째부터는 자유이용권이 이득이야!");
    }

    @Test
    void 전이_숫자는_어떤_출제에서도_본문제와_겹치지_않는다() {
        Random random = new Random(31L);

        for (int i = 0; i < 200; i++) {
            Map<String, Integer> facts = AmusementParkCatalog.initialFacts(random);

            assertThat(AmusementParkCatalog.transfer("ticket", facts).equation())
                    .isNotEqualTo("%,d × %d = %,d".formatted(
                            facts.get("ticket_price"),
                            facts.get("party_count"),
                            facts.get("ticket_price") * facts.get("party_count")));
            assertThat(AmusementParkCatalog.transfer("snack_split", facts).equation())
                    .isNotEqualTo("%,d ÷ %d = %,d".formatted(
                            facts.get("snack_total"),
                            facts.get("payer_count"),
                            facts.get("snack_total") / facts.get("payer_count")));
            assertThat(AmusementParkCatalog.transfer("pass_break_even", facts).equation())
                    .isNotEqualTo("%,d ÷ %,d = %d".formatted(
                            facts.get("day_pass_price"),
                            facts.get("single_ride_price"),
                            facts.get("day_pass_price") / facts.get("single_ride_price")));
        }
    }
}

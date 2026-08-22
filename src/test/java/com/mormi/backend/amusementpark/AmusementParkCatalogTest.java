package com.mormi.backend.amusementpark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mormi.backend.curriculum.AmusementParkCatalog;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AmusementParkCatalogTest {

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
        Map<String, Integer> facts = AmusementParkCatalog.initialFacts();

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
        Map<String, Integer> facts = new LinkedHashMap<>(AmusementParkCatalog.initialFacts());
        facts.put("ticket_price", 3500);
        facts.put("party_count", 4);

        assertThat(AmusementParkCatalog.expectedAnswers("ticket", facts))
                .containsEntry("total_price", 14000);
    }

    @Test
    void verified_facts_는_주어진_값과_정답을_함께_담는다() {
        assertThat(AmusementParkCatalog.verifiedFacts("ticket", AmusementParkCatalog.initialFacts()))
                .containsExactly(
                        Map.entry("ticket_price", 3000),
                        Map.entry("party_count", 2),
                        Map.entry("total_price", 6000));
    }

    @Test
    void 나누어떨어지지_않는_숫자는_출제_자체를_막는다() {
        Map<String, Integer> facts = new LinkedHashMap<>(AmusementParkCatalog.initialFacts());
        facts.put("payer_count", 7);  // 6,000 ÷ 7 은 딱 떨어지지 않는다

        assertThatThrownBy(() -> AmusementParkCatalog.expectedAnswers("snack_split", facts))
                .isInstanceOf(IllegalStateException.class);
    }
}

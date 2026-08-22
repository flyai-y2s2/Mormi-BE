package com.mormi.backend.amusementpark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.AmusementParkCatalog;
import com.mormi.backend.curriculum.AmusementParkCatalog.StageContent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AmusementParkProblemContractTest {

    private final StageContent ticket = AmusementParkCatalog.stage("ticket");
    private final StageContent pass = AmusementParkCatalog.stage("pass_break_even");

    @Test
    void 요구하는_답이_다_있으면_통과한다() {
        assertThat(AmusementParkProblemContract.requireDerivedAnswers(
                ticket, Map.of("total_price", 6000)))
                .containsExactly(Map.entry("total_price", 6000));
    }

    @Test
    void 답이_모자라면_판정하지_않고_거절한다() {
        assertThatThrownBy(() -> AmusementParkProblemContract.requireDerivedAnswers(
                pass, Map.of("break_even_rides", 5)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "answer_missing");
    }

    @Test
    void 이_단계에서_받지_않는_키는_거절한다() {
        Map<String, Integer> answers = new LinkedHashMap<>();
        answers.put("total_price", 6000);
        answers.put("ticket_price", 9999);

        assertThatThrownBy(() -> AmusementParkProblemContract.requireDerivedAnswers(ticket, answers))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "answer_unknown");
    }

    @Test
    void 범위를_벗어난_답은_거절한다() {
        assertThatThrownBy(() -> AmusementParkProblemContract.requireDerivedAnswers(
                ticket, Map.of("total_price", -1)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "answer_range");
    }

    @Test
    void 대화가_돌려준_주어진_값이_방문과_같으면_통과한다() {
        Map<String, Integer> visitFacts = AmusementParkCatalog.initialFacts();

        assertThatCode(() -> AmusementParkProblemContract.requireGivenFactsMatch(
                ticket, visitFacts, Map.of("ticket_price", 3000, "party_count", 2, "total_price", 6000)))
                .doesNotThrowAnyException();
    }

    @Test
    void 대화가_다른_문제를_보고_있으면_통과시키지_않는다() {
        Map<String, Integer> visitFacts = AmusementParkCatalog.initialFacts();

        assertThatThrownBy(() -> AmusementParkProblemContract.requireGivenFactsMatch(
                ticket, visitFacts, Map.of("ticket_price", 3500, "party_count", 2)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "dialogue_completion_fact_mismatch");
    }
}

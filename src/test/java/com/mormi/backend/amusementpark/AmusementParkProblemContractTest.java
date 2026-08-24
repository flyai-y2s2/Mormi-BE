package com.mormi.backend.amusementpark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.AmusementParkCatalog;
import com.mormi.backend.curriculum.AmusementParkCatalog.StageContent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AmusementParkProblemContractTest {

    private final StageContent ticket = AmusementParkCatalog.stage("ticket");

    @Test
    void AI가_보낸_완료_증거의_구조만_검증한다() {
        assertThat(AmusementParkProblemContract.requireVerifiedFacts(
                ticket, Map.of("ticket_price", 3000, "party_count", 2, "total_price", 6000)))
                .containsExactly(
                        Map.entry("ticket_price", 3000),
                        Map.entry("party_count", 2),
                        Map.entry("total_price", 6000));
    }

    @Test
    void 완료_증거가_모자라면_계약_오류다() {
        assertThatThrownBy(() -> AmusementParkProblemContract.requireVerifiedFacts(
                ticket, Map.of("ticket_price", 3000, "party_count", 2)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "dialogue_completion_facts_missing");
    }

    @Test
    void 완료_증거의_범위와_키를_제한한다() {
        assertThatThrownBy(() -> AmusementParkProblemContract.requireVerifiedFacts(
                ticket, Map.of("ticket_price", -1, "party_count", 2, "total_price", 6000)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "dialogue_completion_fact_range");

        Map<String, Integer> withUnknown = new LinkedHashMap<>();
        withUnknown.put("ticket_price", 3000);
        withUnknown.put("party_count", 2);
        withUnknown.put("total_price", 6000);
        withUnknown.put("prompt", 1);
        assertThatThrownBy(() -> AmusementParkProblemContract.requireVerifiedFacts(ticket, withUnknown))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "dialogue_completion_fact_unknown");
    }
}

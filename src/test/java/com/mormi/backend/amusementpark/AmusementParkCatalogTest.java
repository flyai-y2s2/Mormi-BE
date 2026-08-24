package com.mormi.backend.amusementpark;

import static org.assertj.core.api.Assertions.assertThat;

import com.mormi.backend.curriculum.AmusementParkCatalog;
import org.junit.jupiter.api.Test;

class AmusementParkCatalogTest {

    @Test
    void BE는_단계와_AI_시나리오_연결만_소유한다() {
        assertThat(AmusementParkCatalog.stageOrder())
                .containsExactly("ticket", "snack_split", "pass_break_even");
        assertThat(AmusementParkCatalog.stages())
                .extracting(AmusementParkCatalog.StageContent::scenarioId)
                .containsExactly(
                        "amusement_ticket_multiply",
                        "amusement_snack_divide",
                        "amusement_pass_compare");
    }

    @Test
    void 완료_증거_키는_서비스_경계_계약으로만_둔다() {
        assertThat(AmusementParkCatalog.stage("ticket").requiredVerifiedFactKeys())
                .containsExactly("ticket_price", "party_count", "total_price");
        assertThat(AmusementParkCatalog.stage("snack_split").requiredVerifiedFactKeys())
                .containsExactly("snack_total", "payer_count", "per_person");
        assertThat(AmusementParkCatalog.stage("pass_break_even").requiredVerifiedFactKeys())
                .containsExactly(
                        "single_ride_price", "day_pass_price", "break_even_rides", "benefit_from_rides");
    }

    @Test
    void BE_카탈로그에는_교수_콘텐츠_필드가_없다() {
        assertThat(AmusementParkCatalog.StageContent.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "stageId", "scenarioId", "title", "mission", "skill", "requiredVerifiedFactKeys")
                .doesNotContain("prompt", "strategy", "mormiMisconception", "facts", "transfer");
    }
}

package com.mormi.backend.dialogue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public final class DialogueDtos {

    private DialogueDtos() {
    }

    /** 화면에 고정된 카페 문제 사실만 받는다. learner_id와 보존 동의는 BE가 채운다. */
    public record StartCafeDialogueRequest(
            @NotBlank
            @Pattern(regexp = "cafe_queue|cafe_budget_menu|cafe_menu_total|cafe_change")
            String scenarioId,
            @Size(max = 10) Map<String, Object> queueContext,
            @Size(max = 10) Map<String, Object> cafeContext) {
    }
}

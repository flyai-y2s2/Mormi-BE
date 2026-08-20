package com.mormi.backend.dialogue;

import com.mormi.backend.cafe.CafeDtos.CafeContext;
import com.mormi.backend.cafe.CafeDtos.QueueContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class DialogueDtos {

    private DialogueDtos() {
    }

    /**
     * 화면에 고정된 카페 문제 사실만 타입 있는 계약으로 받는다. learner_id와 보존 동의는 BE가 채운다.
     * 값 규칙(줄 인원 범위·카탈로그 대조)은 대화 시작 시 CafeProblemContract 가 검증해,
     * 잘못된 문제로 AI 대화를 끝까지 진행한 뒤 5xx로 실패하는 일을 막는다.
     *
     * @param restart 이미 끝낸 스테이지를 다시 연습하려는 요청. true 면 새 회차 대화를 만들고,
     *                false 면 새로고침 복구로 보아 마지막 회차를 그대로 돌려준다.
     */
    public record StartCafeDialogueRequest(
            @NotBlank
            @Pattern(regexp = "cafe_queue|cafe_budget_menu|cafe_menu_total|cafe_change")
            String scenarioId,
            @Valid QueueContext queueContext,
            @Valid CafeContext cafeContext,
            boolean restart) {
    }
}

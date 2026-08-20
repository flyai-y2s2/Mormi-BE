package com.mormi.backend.dialogue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public final class DialogueDtos {

    private DialogueDtos() {
    }

    /**
     * 화면에 고정된 카페 문제 사실만 받는다. learner_id와 보존 동의는 BE가 채운다.
     *
     * @param startMode restart 면 기존 기록을 보존한 채 새 회차 대화를 만들고,
     *                  resume 이면 마지막 회차를 그대로 이어 준다(없으면 새로 만든다).
     * @param requestId FE가 요청마다 새로 뽑는 멱등키. 네트워크 재시도로 같은 요청이
     *                  중복 도착해도 회차가 여러 개 생기지 않게 한다.
     * @param restart 폐기 예정. start_mode 가 없을 때만 해석한다(true=restart, false=resume).
     */
    public record StartCafeDialogueRequest(
            @NotBlank
            @Pattern(regexp = "cafe_queue|cafe_budget_menu|cafe_menu_total|cafe_change")
            String scenarioId,
            @Size(max = 10) Map<String, Object> queueContext,
            @Size(max = 10) Map<String, Object> cafeContext,
            @Pattern(regexp = "restart|resume") String startMode,
            @Size(max = 100) String requestId,
            boolean restart) {

        /** start_mode 가 오면 그 값을 따르고, 없으면 옛 restart boolean 을 해석한다. */
        public boolean wantsRestart() {
            return startMode != null ? "restart".equals(startMode) : restart;
        }
    }

    /**
     * 홈 가르치기 시작 요청. body 없이 부르면 기존 동작(resume)과 같다.
     *
     * @param startMode restart 면 기존 대화를 보존한 채 새 회차의 첫 턴부터 시작한다.
     * @param requestId FE가 요청마다 새로 뽑는 멱등키.
     */
    public record StartTeachingRequest(
            @Pattern(regexp = "restart|resume") String startMode,
            @Size(max = 100) String requestId) {

        public boolean wantsRestart() {
            return "restart".equals(startMode);
        }
    }
}

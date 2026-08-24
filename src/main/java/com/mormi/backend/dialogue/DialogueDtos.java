package com.mormi.backend.dialogue;

import com.mormi.backend.cafe.CafeDtos.CafeContext;
import com.mormi.backend.cafe.CafeDtos.QueueContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DialogueDtos {

    private DialogueDtos() {
    }

    /**
     * 화면에 고정된 카페 문제 사실만 타입 있는 계약으로 받는다. learner_id와 보존 동의는 BE가 채운다.
     * 값 규칙(줄 인원 범위·카탈로그 대조)은 대화 시작 시 CafeProblemContract 가 검증해,
     * 잘못된 문제로 AI 대화를 끝까지 진행한 뒤 5xx로 실패하는 일을 막는다.
     *
     * @param startMode restart 면 기존 기록을 보존한 채 새 회차 대화를 만들고,
     *                  resume 이면 마지막 회차를 그대로 이어 준다(없으면 새로 만든다).
     * @param requestId FE가 요청마다 새로 뽑는 멱등키. 네트워크 재시도로 같은 요청이
     *                  중복 도착해도 회차가 여러 개 생기지 않게 한다.
     * @param restart 폐기 예정. start_mode 가 없을 때만 해석한다(true=restart, 그 외=resume).
     *                원시형이면 Jackson 3가 누락을 null→boolean 매핑 실패로 거절해 본문 전체가
     *                400 이 되므로, 새 FE처럼 안 보내는 클라이언트를 위해 래퍼로 받는다.
     */
    public record StartCafeDialogueRequest(
            @NotBlank
            @Pattern(regexp = "cafe_queue|cafe_budget_menu|cafe_menu_total|cafe_change")
            String scenarioId,
            @Valid QueueContext queueContext,
            @Valid CafeContext cafeContext,
            @Pattern(regexp = "restart|resume") String startMode,
            @Size(max = 100) String requestId,
            Boolean restart) {

        /** start_mode 가 오면 그 값을 따르고, 없으면 옛 restart boolean 을 해석한다. */
        public boolean wantsRestart() {
            return startMode != null ? "restart".equals(startMode) : Boolean.TRUE.equals(restart);
        }
    }

    /**
     * 놀이동산 대화 시작 요청.
     *
     * <p>카페와 달리 문제 사실을 프런트에서 받지 않는다. 가격과 인원은 방문 시작 시 서버가
     * 고정해 방문 행에 저장했으므로, 대화 컨텍스트도 그 값에서 만든다.
     *
     * @param startMode restart 면 기존 기록을 보존한 채 새 회차 대화를 만들고,
     *                  resume(기본)이면 마지막 회차를 그대로 이어 준다.
     * @param requestId FE가 요청마다 새로 뽑는 멱등키.
     */
    public record StartParkDialogueRequest(
            @NotBlank
            @Pattern(regexp = "amusement_ticket_multiply|amusement_snack_divide|amusement_pass_compare")
            String scenarioId,
            @Pattern(regexp = "restart|resume") String startMode,
            @Size(max = 100) String requestId) {

        public boolean wantsRestart() {
            return "restart".equals(startMode);
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

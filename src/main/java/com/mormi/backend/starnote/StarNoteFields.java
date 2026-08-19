package com.mormi.backend.starnote;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * star_note_created 이벤트에서 파싱한 별노트 값 묶음.
 *
 * <p>소유권(learner_id)과 세션 연결은 여기 없다. 이벤트가 보낸 값을 믿지 않고
 * 대화 기록에서 끌어오기 때문에 서비스가 {@code DialogueConversation} 으로 따로 채운다.
 * 문장과 귀속은 AI 원문 그대로 담는다. BE 가 재작성·합성하지 않는다.
 */
public record StarNoteFields(
        Integer noteVersion,
        String scene,
        String scenarioId,
        String taskId,
        String stage,
        Integer taskIndex,
        String skillId,
        String noteText,
        String attribution,
        String attributionLabel,
        String evidence,
        List<Map<String, Object>> evidenceLinks,
        Boolean active,
        OffsetDateTime noteCreatedAt) {
}

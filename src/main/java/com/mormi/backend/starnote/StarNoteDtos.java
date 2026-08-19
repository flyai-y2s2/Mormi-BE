package com.mormi.backend.starnote;

import java.time.OffsetDateTime;
import java.util.List;

public final class StarNoteDtos {

    private StarNoteDtos() {
    }

    /**
     * 목록 항목 1건. AI가 준 문장·귀속을 재작성·합성 없이 그대로 내보낸다.
     *
     * <p>DB 컬럼은 note_text 지만 API 필드는 이벤트 계약과 같은 text 로 나간다.
     * created_at 은 AI가 노트를 만든 시각(note_created_at)이다. 수신 시각이 아니다.
     */
    public record StarNoteItem(
            String noteId,
            String skillId,
            String text,
            String attribution,
            String attributionLabel,
            String evidence,
            String scene,
            String scenarioId,
            String taskId,
            OffsetDateTime createdAt) {

        public static StarNoteItem from(StarNote note) {
            return new StarNoteItem(
                    note.getNoteId(),
                    note.getSkillId(),
                    note.getNoteText(),
                    note.getAttribution(),
                    note.getAttributionLabel(),
                    note.getEvidence(),
                    note.getScene(),
                    note.getScenarioId(),
                    note.getTaskId(),
                    note.getNoteCreatedAt());
        }
    }

    /**
     * @param nextCursor 다음 페이지를 요청할 때 넘길 커서(이 페이지 마지막 항목의 note_id).
     *     마지막 페이지면 null 이고 non_null 직렬화로 응답에서 빠진다.
     */
    public record StarNoteList(List<StarNoteItem> starNotes, String nextCursor) {
    }
}

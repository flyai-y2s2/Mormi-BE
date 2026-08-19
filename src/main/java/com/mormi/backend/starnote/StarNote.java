package com.mormi.backend.starnote;

import com.mormi.backend.dialogue.DialogueConversation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 별노트 원장 1행. AI 노트 하나당 한 행을 유지한다.
 *
 * <p>같은 노트의 이벤트가 여러 번 도착해도 행은 늘지 않고, note_version 이 더 높은
 * 재발행만 반영한다. 문장(note_text)과 귀속(attribution)은 AI 원문 그대로 보존한다.
 * evidence_links 는 관찰 이벤트보다 먼저 도착할 수 있어 FK 없이 AI 원본 ID 로만 담는다.
 */
@Entity
@Table(name = "star_notes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StarNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 마지막으로 이 행을 만들거나 갱신한 수신함 이벤트. */
    @Column(name = "observation_event_id", nullable = false)
    private Long observationEventId;

    /** AI가 부여하는 노트 멱등 키. 다른 event_id 로 재전송돼도 이 값으로 한 행만 남는다. */
    @Column(name = "note_id", nullable = false, length = 100, updatable = false)
    private String noteId;

    /** 재발행 판별용. 같거나 낮은 버전은 서비스가 걸러서 apply 를 부르지 않는다. */
    @Column(name = "note_version", nullable = false)
    private Integer noteVersion;

    /** 소유권은 이벤트가 보낸 값이 아니라 대화 기록에서 끌어온다. */
    @Column(name = "learner_id", nullable = false, updatable = false)
    private Long learnerId;

    @Column(name = "learning_session_id", updatable = false)
    private Long learningSessionId;

    @Column(name = "conversation_id", nullable = false, length = 100, updatable = false)
    private String conversationId;

    @Column(name = "scene", length = 40)
    private String scene;

    @Column(name = "scenario_id", length = 100)
    private String scenarioId;

    @Column(name = "task_id", length = 120)
    private String taskId;

    @Column(name = "stage", length = 40)
    private String stage;

    @Column(name = "task_index")
    private Integer taskIndex;

    @Column(name = "skill_id", length = 100)
    private String skillId;

    /** 아이에게 보여줄 문장. AI 원문 그대로이며 BE 가 재작성하지 않는다. */
    @Column(name = "note_text", nullable = false)
    private String noteText;

    @Column(name = "attribution", nullable = false, length = 20)
    private String attribution;

    @Column(name = "attribution_label", length = 60)
    private String attributionLabel;

    @Column(name = "evidence", length = 40)
    private String evidence;

    /** [{"observation_id": ..., "source_slot_ids": [...]}]. FK 없음(순서 역전 허용). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_links", columnDefinition = "jsonb")
    private List<Map<String, Object>> evidenceLinks;

    /** 재발행으로 비활성화된 노트는 목록에서 숨긴다. 행은 지우지 않는다. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** AI가 노트를 만든 시각. 목록 정렬 기준이다. */
    @Column(name = "note_created_at", nullable = false)
    private OffsetDateTime noteCreatedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    private StarNote(
            Long observationEventId,
            String noteId,
            DialogueConversation conversation,
            StarNoteFields fields) {
        this.noteId = noteId;
        this.learnerId = conversation.getLearnerId();
        this.learningSessionId = conversation.getLearningSessionId();
        this.conversationId = conversation.getConversationId();
        apply(observationEventId, fields);
    }

    public static StarNote from(
            Long observationEventId,
            String noteId,
            DialogueConversation conversation,
            StarNoteFields fields) {
        return new StarNote(observationEventId, noteId, conversation, fields);
    }

    /** 더 높은 note_version 이 도착했을 때만 호출한다. 버전 비교는 서비스가 한다. */
    public void apply(Long observationEventId, StarNoteFields fields) {
        this.observationEventId = observationEventId;
        this.noteVersion = fields.noteVersion();
        this.scene = fields.scene();
        this.scenarioId = fields.scenarioId();
        this.taskId = fields.taskId();
        this.stage = fields.stage();
        this.taskIndex = fields.taskIndex();
        this.skillId = fields.skillId();
        this.noteText = fields.noteText();
        this.attribution = fields.attribution();
        this.attributionLabel = fields.attributionLabel();
        this.evidence = fields.evidence();
        this.evidenceLinks = fields.evidenceLinks();
        // active 를 안 보낸 재발행은 노트를 숨기려는 의도가 아니므로 활성으로 둔다.
        this.active = fields.active() == null || fields.active();
        this.noteCreatedAt = fields.noteCreatedAt();
    }
}

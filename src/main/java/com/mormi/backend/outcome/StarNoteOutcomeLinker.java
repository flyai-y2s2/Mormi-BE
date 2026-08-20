package com.mormi.backend.outcome;

import com.mormi.backend.starnote.StarNote;
import com.mormi.backend.starnote.StarNoteRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * star_notes 원장과 learning_task_outcomes 의 star_note_* 컬럼을 맞춘다.
 *
 * <p>연결은 항상 원장에서 다시 읽어 계산하므로 몇 번을 호출해도 같은 결과가 된다.
 * 별노트와 outcome 행은 어느 쪽이 먼저 생길지 알 수 없어 두 시점 모두에서 부른다.
 * <ul>
 *   <li>별노트 이벤트 반영 직후 — outcome 행이 이미 있으면 그 자리에서 연결된다.</li>
 *   <li>recompute 직후 — 먼저 도착해 원장에서 기다리던 별노트가 새 행에 채워진다.</li>
 * </ul>
 */
@Service
public class StarNoteOutcomeLinker {

    private final StarNoteRepository starNoteRepository;
    private final LearningTaskOutcomeRepository outcomeRepository;

    public StarNoteOutcomeLinker(
            StarNoteRepository starNoteRepository,
            LearningTaskOutcomeRepository outcomeRepository) {
        this.starNoteRepository = starNoteRepository;
        this.outcomeRepository = outcomeRepository;
    }

    /** 세션·과제 하나의 연결을 원장 기준으로 다시 계산한다. */
    @Transactional
    public void relink(Long learningSessionId, String taskKey) {
        if (learningSessionId == null || taskKey == null) {
            return;
        }
        LearningTaskOutcome outcome = outcomeRepository
                .findByLearningSessionIdAndTaskKey(learningSessionId, taskKey)
                .orElse(null);
        // outcome 행이 아직 없으면 여기서 만들지 않는다. 별노트는 원장에 남아 있으므로
        // 나중에 recompute 가 행을 만들면서 다시 relink 해 채운다(순서 역전 허용).
        if (outcome == null) {
            return;
        }
        StarNote note = starNoteRepository
                .findActiveByTask(learningSessionId, taskKey, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
        // 활성 노트가 없으면(재발행으로 비활성화·과제 이동) 연결을 풀어 원장과 어긋나지 않게 한다.
        if (note == null) {
            outcome.linkStarNote(null, null, null);
        } else {
            outcome.linkStarNote(note.getNoteId(), note.getAttribution(), note.getEvidence());
        }
        outcomeRepository.save(outcome);
    }
}

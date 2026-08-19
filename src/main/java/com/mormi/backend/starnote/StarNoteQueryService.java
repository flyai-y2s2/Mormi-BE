package com.mormi.backend.starnote;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.learner.LearnerService;
import com.mormi.backend.starnote.StarNoteDtos.StarNoteItem;
import com.mormi.backend.starnote.StarNoteDtos.StarNoteList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학습자별 별노트 목록 조회. 정렬은 AI 생성 시각 최신순, 동률이면 note_id 내림차순으로 고정한다.
 *
 * <p>커서(keyset) 페이지네이션: 커서는 직전 페이지 마지막 항목의 note_id 다.
 * OFFSET 과 달리 조회 사이에 새 노트가 끼어들어도 중복·누락이 생기지 않는다.
 */
@Service
public class StarNoteQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final StarNoteRepository starNoteRepository;
    private final LearnerService learnerService;

    public StarNoteQueryService(StarNoteRepository starNoteRepository, LearnerService learnerService) {
        this.starNoteRepository = starNoteRepository;
        this.learnerService = learnerService;
    }

    @Transactional(readOnly = true)
    public StarNoteList list(Long learnerId, Long authenticatedId, Integer limit, String cursor) {
        learnerService.requireSelf(learnerId, authenticatedId);

        int pageSize = clamp(limit);
        // 다음 페이지가 있는지 알기 위해 한 개 더 읽는다. 넘치면 next_cursor 를 채운다.
        Pageable window = PageRequest.of(0, pageSize + 1);
        List<StarNote> rows = cursor == null || cursor.isBlank()
                ? starNoteRepository.findPage(learnerId, window)
                : starNoteRepository.findPageAfter(
                        learnerId, resolveCursor(learnerId, cursor), cursor, window);

        boolean hasNext = rows.size() > pageSize;
        List<StarNote> page = hasNext ? rows.subList(0, pageSize) : rows;
        return new StarNoteList(
                page.stream().map(StarNoteItem::from).toList(),
                hasNext ? page.get(page.size() - 1).getNoteId() : null);
    }

    /**
     * 커서(note_id)로 keyset 기준점의 정렬 값을 복원한다.
     *
     * <p>모르는 커서를 조용히 1페이지로 처리하면 FE 무한 스크롤이 처음부터 다시 도는 루프에
     * 빠질 수 있어 명시적으로 422 를 준다. 남의 노트 ID 도 같은 응답이라 존재 여부를 떠볼 수 없다.
     */
    private java.time.OffsetDateTime resolveCursor(Long learnerId, String cursor) {
        return starNoteRepository.findByNoteId(cursor)
                .filter(note -> note.getLearnerId().equals(learnerId))
                .map(StarNote::getNoteCreatedAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "invalid_cursor", "알 수 없는 커서입니다: " + cursor));
    }

    private int clamp(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}

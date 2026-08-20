package com.mormi.backend.starnote;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StarNoteRepository extends JpaRepository<StarNote, Long> {

    Optional<StarNote> findByNoteId(String noteId);

    /**
     * 목록 첫 페이지. 정렬은 AI 생성 시각 최신순, 동률이면 note_id 내림차순으로 고정한다.
     *
     * <p>정렬이 완전히 결정적이어야 커서(마지막 항목)를 기준으로 다음 페이지를 이어도
     * 중복·누락이 생기지 않는다. 비활성 노트는 목록에서 뺀다.
     */
    @Query("""
            SELECT n FROM StarNote n
            WHERE n.learnerId = :learnerId AND n.active = true
            ORDER BY n.noteCreatedAt DESC, n.noteId DESC
            """)
    List<StarNote> findPage(@Param("learnerId") Long learnerId, Pageable pageable);

    /**
     * 커서 다음 페이지. (note_created_at, note_id) 가 커서보다 뒤(정렬상 아래)인 행만 가져온다.
     *
     * <p>OFFSET 방식과 달리 조회 사이에 새 노트가 끼어들어도 이미 본 항목이
     * 다시 나오거나 건너뛰어지지 않는다. keyset 페이지네이션.
     */
    @Query("""
            SELECT n FROM StarNote n
            WHERE n.learnerId = :learnerId AND n.active = true
              AND (n.noteCreatedAt < :cursorCreatedAt
                   OR (n.noteCreatedAt = :cursorCreatedAt AND n.noteId < :cursorNoteId))
            ORDER BY n.noteCreatedAt DESC, n.noteId DESC
            """)
    List<StarNote> findPageAfter(
            @Param("learnerId") Long learnerId,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorNoteId") String cursorNoteId,
            Pageable pageable);

    /**
     * 과제(outcome 행)에 연결할 대표 별노트 후보. 목록과 같은 정렬을 써서
     * 같은 과제에 노트가 여러 개면 항상 최신 활성 노트 하나로 결정된다.
     */
    @Query("""
            SELECT n FROM StarNote n
            WHERE n.learningSessionId = :learningSessionId
              AND n.taskId = :taskId AND n.active = true
            ORDER BY n.noteCreatedAt DESC, n.noteId DESC
            """)
    List<StarNote> findActiveByTask(
            @Param("learningSessionId") Long learningSessionId,
            @Param("taskId") String taskId,
            Pageable pageable);
}

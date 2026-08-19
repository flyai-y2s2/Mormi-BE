package com.mormi.backend.cafe;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CafeVisitRepository extends JpaRepository<CafeVisit, Long> {

    Optional<CafeVisit> findByPublicId(String publicId);

    Optional<CafeVisit> findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(Long learnerId);

    /** 완료 여부와 무관한 최신 방문. 끝낸 방문을 연습 모드로 다시 여는 데 쓴다. */
    Optional<CafeVisit> findFirstByLearnerIdOrderByIdDesc(Long learnerId);

    List<CafeVisit> findByLearnerIdAndCompletedAtIsNotNullOrderByCompletedAtAsc(Long learnerId);

    List<CafeVisit> findByLearnerIdAndCompletedAtGreaterThanEqualAndCompletedAtLessThanOrderByCompletedAtAsc(
            Long learnerId, OffsetDateTime startInclusive, OffsetDateTime endExclusive);

    int countByLearnerIdAndCompletedAtIsNotNull(Long learnerId);
}

package com.mormi.backend.amusementpark;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AmusementParkVisitRepository extends JpaRepository<AmusementParkVisit, Long> {

    Optional<AmusementParkVisit> findByPublicId(String publicId);

    Optional<AmusementParkVisit> findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(Long learnerId);

    /** 완료 여부와 무관한 최신 방문. 끝낸 방문을 연습 모드로 다시 여는 데 쓴다. */
    Optional<AmusementParkVisit> findFirstByLearnerIdOrderByIdDesc(Long learnerId);
}

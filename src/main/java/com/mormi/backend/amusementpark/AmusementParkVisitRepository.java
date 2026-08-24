package com.mormi.backend.amusementpark;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AmusementParkVisitRepository extends JpaRepository<AmusementParkVisit, Long> {

    Optional<AmusementParkVisit> findByPublicId(String publicId);

    Optional<AmusementParkVisit> findFirstByLearnerIdAndCompletedAtIsNullOrderByIdDesc(Long learnerId);

    /** 완료 여부와 무관한 최신 방문. 새 방문 숫자가 직전 방문과 같지 않게 뽑을 때 쓴다. */
    Optional<AmusementParkVisit> findFirstByLearnerIdOrderByIdDesc(Long learnerId);
}

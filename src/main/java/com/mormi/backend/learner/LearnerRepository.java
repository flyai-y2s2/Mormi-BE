package com.mormi.backend.learner;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

public interface LearnerRepository extends JpaRepository<Learner, Long> {

    Optional<Learner> findByResearchCode(String researchCode);

    /** 인증된 계정에서 학습자 프로필을 찾는 매 요청 경로. account_id UNIQUE 를 탄다. */
    Optional<Learner> findByAccountId(Long accountId);

    boolean existsByResearchCode(String researchCode);

    List<Learner> findByDisplayNameContainingIgnoreCaseOrderByDisplayNameAscIdAsc(
            String displayName, Pageable pageable);
}

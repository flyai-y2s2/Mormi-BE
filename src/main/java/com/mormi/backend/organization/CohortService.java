package com.mormi.backend.organization;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.learner.Learner;
import com.mormi.backend.learner.LearnerRepository;
import com.mormi.backend.organization.CohortDtos.CohortLearnerResponse;
import com.mormi.backend.organization.CohortDtos.CohortReportResponse;
import com.mormi.backend.organization.CohortDtos.CohortResponse;
import com.mormi.backend.organization.CohortDtos.IssuedResearchCodeResponse;
import com.mormi.backend.report.ReportSnapshotService;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 교사의 학급 관리. 모든 진입점이 "요청 교사가 그 학급의 기관 소속인지"를 먼저 검증해
 * 다른 기관의 아이 목록·리포트가 새지 않는다.
 */
@Service
public class CohortService {

    /** 숫자 0·1, 알파벳 O·I·L 처럼 헷갈리는 글자를 뺀 학급 코드 알파벳. */
    private static final String CLASS_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CLASS_CODE_LENGTH = 6;

    private final CohortRepository cohortRepository;
    private final CohortResearchCodeRepository researchCodeRepository;
    private final EducatorRepository educatorRepository;
    private final LearnerRepository learnerRepository;
    private final LearnerEnrollmentRepository enrollmentRepository;
    private final ReportSnapshotService reportSnapshotService;
    private final SecureRandom random = new SecureRandom();

    public CohortService(
            CohortRepository cohortRepository,
            CohortResearchCodeRepository researchCodeRepository,
            EducatorRepository educatorRepository,
            LearnerRepository learnerRepository,
            LearnerEnrollmentRepository enrollmentRepository,
            ReportSnapshotService reportSnapshotService) {
        this.cohortRepository = cohortRepository;
        this.researchCodeRepository = researchCodeRepository;
        this.educatorRepository = educatorRepository;
        this.learnerRepository = learnerRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.reportSnapshotService = reportSnapshotService;
    }

    @Transactional
    public CohortResponse create(Long educatorId, String name) {
        Educator educator = requireEducator(educatorId);
        Cohort cohort = cohortRepository.save(
                Cohort.of(educator.getOrganizationId(), name.trim(), newClassCode()));
        return CohortResponse.of(cohort);
    }

    /** 담당 학급 목록. 파일럿은 교사-학급 배정 없이 기관 단위로 공유한다. */
    @Transactional(readOnly = true)
    public List<CohortResponse> list(Long educatorId) {
        Educator educator = requireEducator(educatorId);
        return cohortRepository.findByOrganizationIdOrderByIdAsc(educator.getOrganizationId())
                .stream().map(CohortResponse::of).toList();
    }

    /**
     * 참여 번호 사전 발급. 이미 그 번호로 가입한 아이가 있으면 이 학급에 소급 재적되고,
     * 응답의 learnerId 로 표시된다. 아직 없는 번호는 아이가 가입할 때 자동 재적된다.
     */
    @Transactional
    public List<IssuedResearchCodeResponse> issueResearchCodes(
            Long educatorId, Long cohortId, List<String> codes) {
        Educator educator = requireEducator(educatorId);
        Cohort cohort = requireCohortInOrganization(educator, cohortId);

        List<IssuedResearchCodeResponse> issued = new ArrayList<>();
        for (String raw : codes) {
            String code = raw.trim();
            if (researchCodeRepository.existsByCode(code)) {
                throw ApiException.conflict(
                        "research_code_issued", "이미 발급된 참여 번호입니다: " + code);
            }
            researchCodeRepository.save(
                    CohortResearchCode.issue(cohort.getId(), code, educator.getId()));
            Long enrolledLearnerId = learnerRepository.findByResearchCode(code)
                    .map(learner -> enroll(learner.getId(), cohort.getId()))
                    .orElse(null);
            issued.add(new IssuedResearchCodeResponse(code, enrolledLearnerId));
        }
        return issued;
    }

    @Transactional(readOnly = true)
    public List<CohortLearnerResponse> learners(Long educatorId, Long cohortId) {
        Educator educator = requireEducator(educatorId);
        Cohort cohort = requireCohortInOrganization(educator, cohortId);

        List<LearnerEnrollment> enrollments =
                enrollmentRepository.findByCohortIdAndLeftAtIsNull(cohort.getId());
        Map<Long, Learner> learners = learnerRepository
                .findAllById(enrollments.stream().map(LearnerEnrollment::getLearnerId).toList())
                .stream().collect(Collectors.toMap(Learner::getId, Function.identity()));

        return enrollments.stream()
                .map(enrollment -> {
                    Learner learner = learners.get(enrollment.getLearnerId());
                    return new CohortLearnerResponse(
                            learner.getId(), learner.getDisplayName(),
                            learner.getResearchCode(), enrollment.getEnrolledAt());
                })
                .sorted((a, b) -> a.displayName().compareTo(b.displayName()))
                .toList();
    }

    /** 학급 리포트. 기간을 주지 않으면 최근 7일을 집계한다. */
    @Transactional
    public CohortReportResponse report(
            Long educatorId, Long cohortId, OffsetDateTime from, OffsetDateTime to) {
        Educator educator = requireEducator(educatorId);
        Cohort cohort = requireCohortInOrganization(educator, cohortId);

        OffsetDateTime periodEnd = to != null ? to : OffsetDateTime.now();
        OffsetDateTime periodStart = from != null ? from : periodEnd.minusDays(7);
        return CohortReportResponse.of(
                reportSnapshotService.snapshotForCohort(cohort.getId(), periodStart, periodEnd));
    }

    private Educator requireEducator(Long educatorId) {
        return educatorRepository.findById(educatorId)
                .orElseThrow(() -> ApiException.notFound("교사를 찾을 수 없습니다."));
    }

    /** 다른 기관의 학급은 존재 여부도 숨길 이유가 없어 403 으로 명확히 거절한다. */
    private Cohort requireCohortInOrganization(Educator educator, Long cohortId) {
        Cohort cohort = cohortRepository.findById(cohortId)
                .orElseThrow(() -> ApiException.notFound("학급을 찾을 수 없습니다."));
        if (!cohort.getOrganizationId().equals(educator.getOrganizationId())) {
            throw ApiException.forbidden("다른 기관의 학급에 접근할 수 없습니다.");
        }
        return cohort;
    }

    /** 재적은 한 번만. 이미 재적 중이면 그대로 두고 학습자 ID 만 돌려준다. */
    private Long enroll(Long learnerId, Long cohortId) {
        if (!enrollmentRepository.existsByLearnerIdAndCohortIdAndLeftAtIsNull(learnerId, cohortId)) {
            enrollmentRepository.save(LearnerEnrollment.of(learnerId, cohortId));
        }
        return learnerId;
    }

    private String newClassCode() {
        // 31^6 ≈ 9억 조합이라 충돌은 사실상 없지만, UNIQUE 위반 대신 재시도로 처리한다.
        while (true) {
            StringBuilder code = new StringBuilder(CLASS_CODE_LENGTH);
            for (int i = 0; i < CLASS_CODE_LENGTH; i++) {
                code.append(CLASS_CODE_ALPHABET.charAt(random.nextInt(CLASS_CODE_ALPHABET.length())));
            }
            if (cohortRepository.findByClassCode(code.toString()).isEmpty()) {
                return code.toString();
            }
        }
    }
}

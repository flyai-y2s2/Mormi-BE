package com.mormi.backend.learner;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동의 장부를 쌓는다. 상태 캐시(learners 컬럼) 갱신은 호출하는 쪽 책임이다.
 *
 * <p>상태가 바뀔 때: 이전 활성 행에 withdrawn_at 을 찍고 새 행을 추가한다.
 * 같은 상태가 반복 전송되면 행을 늘리지 않는다. 재전송이 장부를 오염시키지 않기 위해서다.
 */
@Service
public class ConsentRecordService {

    /** 파일럿 동의 문서 버전. 문서가 개정되면 올린다. */
    public static final String CURRENT_POLICY_VERSION = "pilot-v1";

    private final ConsentRecordRepository consentRecordRepository;

    public ConsentRecordService(ConsentRecordRepository consentRecordRepository) {
        this.consentRecordRepository = consentRecordRepository;
    }

    /**
     * 학습자 생성 시점의 동의 상태를 기준선으로 남긴다.
     * collectedBy 는 동의를 받은 교사·연구자 표식이며 모르면 null 로 남긴다.
     */
    @Transactional
    public void recordBaseline(Long learnerId, boolean granted, String collectedBy) {
        consentRecordRepository.save(ConsentRecord.collect(
                learnerId, ConsentRecord.SCOPE_CONVERSATION_STORAGE,
                CURRENT_POLICY_VERSION, granted, collectedBy));
    }

    @Transactional
    public void recordChange(Long learnerId, boolean granted, String collectedBy) {
        ConsentRecord active = consentRecordRepository
                .findFirstByLearnerIdAndScopeAndWithdrawnAtIsNullOrderByIdDesc(
                        learnerId, ConsentRecord.SCOPE_CONVERSATION_STORAGE)
                .orElse(null);
        if (active != null && active.isGranted() == granted) {
            return;
        }
        if (active != null) {
            active.withdraw();
        }
        consentRecordRepository.save(ConsentRecord.collect(
                learnerId, ConsentRecord.SCOPE_CONVERSATION_STORAGE,
                CURRENT_POLICY_VERSION, granted, collectedBy));
    }
}

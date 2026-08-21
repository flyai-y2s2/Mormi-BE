package com.mormi.backend.learner;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.learner.LearnerDtos.ConversationConsentRequest;
import com.mormi.backend.learner.LearnerDtos.LearnerResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearnerService {

    private final LearnerRepository learnerRepository;
    private final ConsentRecordService consentRecordService;

    public LearnerService(
            LearnerRepository learnerRepository, ConsentRecordService consentRecordService) {
        this.learnerRepository = learnerRepository;
        this.consentRecordService = consentRecordService;
    }

    @Transactional(readOnly = true)
    public LearnerResponse get(Long requestedId, Long authenticatedId) {
        requireSelf(requestedId, authenticatedId);
        return LearnerResponse.of(require(requestedId), null);
    }

    @Transactional
    public LearnerResponse updateConversationConsent(
            Long learnerId, ConversationConsentRequest request) {
        Learner learner = require(learnerId);
        learner.applyConsent(request.conversationStorageConsent(), request.retentionPolicy());
        // 상태 캐시만 바꾸면 이전 동의가 사라진다. 장부에 철회·재동의를 함께 남긴다.
        // 아이 기기에서 스스로 바꾼 것이라 수집 주체(collected_by)는 비워 둔다.
        consentRecordService.recordChange(learnerId, learner.isConversationStorageConsent(), null);
        return LearnerResponse.of(learner, null);
    }

    @Transactional(readOnly = true)
    public Learner require(Long learnerId) {
        return learnerRepository.findById(learnerId)
                .orElseThrow(() -> ApiException.notFound("학습자를 찾을 수 없습니다."));
    }

    /** 다른 아이의 데이터를 읽지 못하게 소유권을 확인한다. */
    public void requireSelf(Long requestedId, Long authenticatedId) {
        if (!authenticatedId.equals(requestedId)) {
            throw ApiException.forbidden("다른 학습자의 데이터에 접근할 수 없습니다.");
        }
    }
}

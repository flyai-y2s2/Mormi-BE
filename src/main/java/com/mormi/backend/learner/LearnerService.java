package com.mormi.backend.learner;

import com.mormi.backend.auth.TokenHasher;
import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.CurriculumCatalog;
import com.mormi.backend.learner.LearnerDtos.CreateLearnerRequest;
import com.mormi.backend.learner.LearnerDtos.ConversationConsentRequest;
import com.mormi.backend.learner.LearnerDtos.LearnerResponse;
import com.mormi.backend.reward.RewardService;
import com.mormi.backend.reward.RewardSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearnerService {

    private final LearnerRepository learnerRepository;
    private final TokenHasher tokenHasher;
    private final RewardService rewardService;

    public LearnerService(
            LearnerRepository learnerRepository, TokenHasher tokenHasher, RewardService rewardService) {
        this.learnerRepository = learnerRepository;
        this.tokenHasher = tokenHasher;
        this.rewardService = rewardService;
    }

    /**
     * 온보딩. 같은 연구 코드로 다시 들어오면 새 학습자를 만들지 않고
     * 기존 학습자에 이름만 갱신한 뒤 새 토큰을 발급한다.
     * 아이가 기기를 바꿔도 진행도가 이어지고, 서로 다른 아이가 한 사람으로 섞이지 않는다.
     */
    @Transactional
    public LearnerResponse createOrRestore(CreateLearnerRequest request) {
        String code = request.researchCode().trim();
        String token = tokenHasher.newToken();

        Learner learner = learnerRepository.findByResearchCode(code).orElse(null);
        if (learner == null) {
            learner = learnerRepository.save(Learner.create(request.displayName().trim(), code, tokenHasher.hash(token)));
            // 지갑 시작 잔액을 원장 첫 줄로 남긴다. 학습자당 한 번만 적립된다.
            rewardService.grant(
                    learner.getId(), null, RewardSource.SEED, CurriculumCatalog.WALLET_SEED, "seed:" + learner.getId());
        } else {
            learner.setDisplayName(request.displayName().trim());
            learner.setTokenHash(tokenHasher.hash(token));
        }
        return LearnerResponse.of(learner, token);
    }

    /** 연구 코드로 기존 학습자 복구. 없으면 404. */
    @Transactional
    public LearnerResponse restore(String researchCode) {
        Learner learner = learnerRepository.findByResearchCode(researchCode.trim())
                .orElseThrow(() -> ApiException.notFound("등록되지 않은 연구 코드입니다."));
        String token = tokenHasher.newToken();
        learner.setTokenHash(tokenHasher.hash(token));
        return LearnerResponse.of(learner, token);
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

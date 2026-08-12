package com.mormi.backend.reward;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RewardService {

    private final RewardLedgerRepository rewardLedgerRepository;

    public RewardService(RewardLedgerRepository rewardLedgerRepository) {
        this.rewardLedgerRepository = rewardLedgerRepository;
    }

    /**
     * 같은 멱등키가 이미 있으면 아무것도 적지 않고 0을 돌려준다.
     * 유니크 제약과 사전 조회를 함께 써서 동시 요청에서도 한 번만 적립된다.
     *
     * @return 실제로 적립된 금액
     */
    @Transactional
    public int grant(Long learnerId, Long learningSessionId, RewardSource source, int amount, String idempotencyKey) {
        if (amount == 0 || rewardLedgerRepository.existsByIdempotencyKey(idempotencyKey)) {
            return 0;
        }
        try {
            rewardLedgerRepository.saveAndFlush(
                    RewardLedger.of(learnerId, learningSessionId, source, amount, idempotencyKey));
            return amount;
        } catch (DataIntegrityViolationException duplicate) {
            return 0;
        }
    }

    @Transactional(readOnly = true)
    public int walletBalance(Long learnerId) {
        return rewardLedgerRepository.sumAmountByLearnerId(learnerId);
    }

    @Transactional(readOnly = true)
    public int sessionReward(Long learningSessionId, RewardSource source) {
        return rewardLedgerRepository.sumAmountBySessionAndSource(learningSessionId, source.value());
    }
}

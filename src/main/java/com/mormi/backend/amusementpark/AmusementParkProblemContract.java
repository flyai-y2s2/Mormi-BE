package com.mormi.backend.amusementpark;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.AmusementParkCatalog.StageContent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mormi-AI가 검증해 보낸 놀이동산 완료 증거의 서비스 경계 계약.
 *
 * <p>BE는 산술식이나 정답을 소유·재계산하지 않는다. 시나리오별 허용 키와 안전한 숫자 범위만
 * 검사하며, 값의 의미와 정답 여부는 Mormi-AI의 결정적 교육 엔진이 책임진다.
 */
public final class AmusementParkProblemContract {

    /** 생활수학 범위를 크게 벗어난 계약 오류를 차단하는 상한. */
    private static final int MAX_ANSWER = 1_000_000;

    private AmusementParkProblemContract() {
    }

    /**
     * AI가 검증한 완료 증거의 구조만 확인한다. 산술 정답은 AI의 결정적 교육 엔진이 판정한다.
     */
    public static Map<String, Integer> requireVerifiedFacts(
            StageContent content, Map<String, Integer> facts) {
        if (facts == null) {
            throw ApiException.serviceUnavailable(
                    "dialogue_completion_facts_missing", "놀이동산 완료 사실이 없습니다.");
        }
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (String key : content.requiredVerifiedFactKeys()) {
            Integer value = facts.get(key);
            if (value == null) {
                throw ApiException.serviceUnavailable(
                        "dialogue_completion_facts_missing", "놀이동산 완료 사실이 없습니다: " + key);
            }
            if (value < 0 || value > MAX_ANSWER) {
                throw ApiException.serviceUnavailable(
                        "dialogue_completion_fact_range", "놀이동산 완료 사실 범위를 벗어났습니다: " + key);
            }
            normalized.put(key, value);
        }
        for (String key : facts.keySet()) {
            if (!content.requiredVerifiedFactKeys().contains(key)) {
                throw ApiException.serviceUnavailable(
                        "dialogue_completion_fact_unknown", "지원하지 않는 놀이동산 완료 사실입니다: " + key);
            }
        }
        return normalized;
    }

}

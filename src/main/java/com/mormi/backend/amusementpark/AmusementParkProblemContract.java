package com.mormi.backend.amusementpark;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.AmusementParkCatalog.StageContent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FE·AI·BE가 함께 쓰는 놀이동산 문제 사실의 공통 계약.
 *
 * <p>카페와 같은 원칙이다. 계약 위반은 AI 대화를 끝까지 진행시킨 뒤 5xx 로 무너뜨리지 않고,
 * 제출·대화 시작 경계에서 안정적인 코드의 4xx 로 거절한다.
 */
public final class AmusementParkProblemContract {

    /** 생활수학 범위의 상한. 아이가 오타로 큰 수를 넣어도 판정 이전에 걸린다. */
    private static final int MAX_ANSWER = 1_000_000;

    private AmusementParkProblemContract() {
    }

    /**
     * 제출된 답이 이 스테이지가 요구하는 값 그대로인지 확인한다.
     * 키가 모자라거나 남으면 판정 자체를 하지 않는다.
     */
    public static Map<String, Integer> requireDerivedAnswers(
            StageContent content, Map<String, Integer> answers) {
        if (answers == null) {
            throw ApiException.badRequest("answers_required", "답이 필요합니다.");
        }
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (String key : content.derivedKeys()) {
            Integer value = answers.get(key);
            if (value == null) {
                throw ApiException.badRequest(
                        "answer_missing", "이 단계에 필요한 답이 없습니다: " + key);
            }
            if (value < 0 || value > MAX_ANSWER) {
                throw ApiException.badRequest(
                        "answer_range", "답의 범위를 벗어났습니다: %s=%d".formatted(key, value));
            }
            normalized.put(key, value);
        }
        for (String key : answers.keySet()) {
            if (!content.derivedKeys().contains(key)) {
                throw ApiException.badRequest(
                        "answer_unknown", "이 단계에서 받지 않는 답입니다: " + key);
            }
        }
        return normalized;
    }

    /**
     * AI가 돌려준 verified_facts 의 주어진 값이 방문에 고정된 값과 같은지 확인한다.
     *
     * <p>다르면 화면·AI·서버가 서로 다른 문제를 보고 있다는 뜻이라 통과시키면 안 된다.
     * 아이 잘못이 아니므로 4xx 가 아니라 재시도를 유도하는 503 으로 돌려준다.
     */
    public static void requireGivenFactsMatch(
            StageContent content,
            Map<String, Integer> visitFacts,
            Map<String, Integer> submittedFacts) {
        for (String key : content.factKeys()) {
            Integer expected = visitFacts.get(key);
            Integer submitted = submittedFacts.get(key);
            if (expected == null || !expected.equals(submitted)) {
                throw ApiException.serviceUnavailable(
                        "dialogue_completion_fact_mismatch",
                        "검증된 사실이 방문에 고정된 문제와 일치하지 않습니다: %s (방문 %s, 대화 %s)"
                                .formatted(key, expected, submitted));
            }
        }
    }
}

package com.mormi.backend.curriculum;

import java.util.List;

/**
 * 놀이동산 방문의 라우팅·진행 계약.
 *
 * <p>이 클래스는 문제나 교수 콘텐츠를 소유하지 않는다. 문제 숫자, 정답, 전략, 오개념,
 * 모르미 대사, 힌트, 시각 표상과 전이 문제의 유일한 원장은 Mormi-AI다. BE에는 방문 단계와
 * AI 시나리오를 연결하고 완료 증거의 구조를 검증하는 데 필요한 최소 메타데이터만 둔다.
 */
public final class AmusementParkCatalog {

    private AmusementParkCatalog() {
    }

    public static final String THEME_ID = "amusement_park";

    /**
     * 지도 UI와 서비스 간 경계에 필요한 안정적인 계약.
     *
     * @param requiredVerifiedFactKeys AI가 완료 증거로 보내야 하는 키. 값과 정답식은 AI가 소유한다.
     */
    public record StageContent(
            String stageId,
            String scenarioId,
            String title,
            String mission,
            String skill,
            List<String> requiredVerifiedFactKeys) {
    }

    private static final List<StageContent> STAGES = List.of(
            new StageContent(
                    "ticket",
                    "amusement_ticket_multiply",
                    "매표소",
                    "우리 일행 표 사기",
                    "multiply",
                    List.of("ticket_price", "party_count", "total_price")),
            new StageContent(
                    "snack_split",
                    "amusement_snack_divide",
                    "간식가게",
                    "간식값 똑같이 나눠 내기",
                    "divide",
                    List.of("snack_total", "payer_count", "per_person")),
            new StageContent(
                    "pass_break_even",
                    "amusement_pass_compare",
                    "자유이용권 창구",
                    "자유이용권이 이득인지 따져보기",
                    "compare",
                    List.of(
                            "single_ride_price",
                            "day_pass_price",
                            "break_even_rides",
                            "benefit_from_rides")));

    public static List<StageContent> stages() {
        return STAGES;
    }

    public static List<String> stageOrder() {
        return STAGES.stream().map(StageContent::stageId).toList();
    }

    public static StageContent stage(String stageId) {
        return STAGES.stream()
                .filter(stage -> stage.stageId().equals(stageId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown amusement park stage: " + stageId));
    }

    public static StageContent stageByScenarioId(String scenarioId) {
        return STAGES.stream()
                .filter(stage -> stage.scenarioId().equals(scenarioId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown amusement park scenario: " + scenarioId));
    }
}

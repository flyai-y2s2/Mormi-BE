package com.mormi.backend.curriculum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 놀이동산 생활수학 3스테이지의 서버 소유 콘텐츠와 판정 규칙.
 *
 * <p>카페는 문제 본문과 메뉴판을 프런트 정적 커리큘럼이 갖고 있지만, 놀이동산은 화면 문구까지
 * 서버가 내려준다. 프런트가 정답·설명문·전이 문장을 임의로 만들지 못하게 하려는 계약이다.
 *
 * <p>가격과 인원은 방문 시작 시 한 번 고정되어 방문 행에 저장된다. 여기 값은 그 초기값이고,
 * 판정은 언제나 방문에 저장된 값으로 한다. 나중에 방문마다 숫자를 다르게 뽑고 싶어지면
 * {@link #initialFacts()} 만 바꾸면 되고 판정식은 그대로 쓴다.
 */
public final class AmusementParkCatalog {

    private AmusementParkCatalog() {
    }

    public static final String THEME_ID = "amusement_park";

    /** 화면에 그대로 표시되는 주어진 사실 한 줄. */
    public record Fact(String key, String label, int value, String unit) {
    }

    /** 배운 전략을 새 숫자에 다시 적용해 보는 전이 턴. 문장은 서버가 소유한다. */
    public record Transfer(String prompt, String equation, String conclusion) {
    }

    /**
     * 스테이지 한 칸의 콘텐츠.
     *
     * @param factKeys    화면에 주어지는 값의 키. 방문에 고정 저장된다.
     * @param derivedKeys 아이가 직접 구해야 하는 값의 키. 정오 판정 대상이다.
     */
    public record StageContent(
            String stageId,
            String scenarioId,
            String title,
            String mission,
            String skill,
            String strategy,
            String mormiMisconception,
            String prompt,
            List<Fact> facts,
            List<String> derivedKeys,
            Transfer transfer) {

        public List<String> factKeys() {
            return facts.stream().map(Fact::key).toList();
        }

        /** 대화 완료 시 AI가 반드시 채워 보내야 하는 키. 주어진 값 + 구한 값이다. */
        public List<String> requiredVerifiedFactKeys() {
            return java.util.stream.Stream.concat(factKeys().stream(), derivedKeys.stream()).toList();
        }
    }

    private static final StageContent TICKET = new StageContent(
            "ticket",
            "amusement_ticket_multiply",
            "매표소",
            "우리 일행 표 사기",
            "multiply",
            "같은 돈이 여러 번이면 곱하면 돼",
            "표가 여러 장이어도 한 장 값만 내면 되는 줄 알았어.",
            "1인 입장료와 일행 수를 이용해 총액을 설명해 주세요.",
            List.of(
                    new Fact("ticket_price", "1인 입장료", 3000, "원"),
                    new Fact("party_count", "우리 일행", 2, "명")),
            List.of("total_price"),
            new Transfer(
                    "그럼 1인 3,500원이고 4명이면?",
                    "3,500 × 4 = 14,000",
                    "3,500원을 네 번 더한 것과 같으니까 14,000원이야!"));

    private static final StageContent SNACK_SPLIT = new StageContent(
            "snack_split",
            "amusement_snack_divide",
            "간식가게",
            "간식값 똑같이 나눠 내기",
            "divide",
            "여럿이 똑같이 나눠 내면 나누면 돼",
            "간식을 같이 먹어도 산 사람만 돈을 내는 줄 알았어.",
            "간식 전체 값과 나눠 낼 사람 수를 이용해 한 사람이 낼 돈을 설명해 주세요.",
            List.of(
                    new Fact("snack_total", "간식 전체 값", 6000, "원"),
                    new Fact("payer_count", "나눠 낼 사람", 3, "명")),
            List.of("per_person"),
            new Transfer(
                    "그럼 8,000원을 4명이 나눠 내면?",
                    "8,000 ÷ 4 = 2,000",
                    "8,000원을 똑같이 네 묶음으로 나눴으니까 한 사람은 2,000원이야!"));

    private static final StageContent PASS_BREAK_EVEN = new StageContent(
            "pass_break_even",
            "amusement_pass_compare",
            "자유이용권 창구",
            "자유이용권이 이득인지 따져보기",
            "compare",
            "낱개 값을 계속 더해서 묶음 값과 견줘 보면 돼",
            "자유이용권이 더 비싸 보이니까 무조건 손해인 줄 알았어.",
            "1회 이용권 값과 자유이용권 값을 이용해 몇 번부터 자유이용권이 이득인지 설명해 주세요.",
            List.of(
                    new Fact("single_ride_price", "1회 이용권", 2000, "원"),
                    new Fact("day_pass_price", "자유이용권", 10000, "원")),
            List.of("break_even_rides", "benefit_from_rides"),
            new Transfer(
                    "그럼 1회 3,000원이고 자유이용권이 12,000원이면?",
                    "12,000 ÷ 3,000 = 4",
                    "네 번 타면 본전이고, 다섯 번째부터는 자유이용권이 이득이야!"));

    /** 이슈 계약의 스테이지 순서. AmusementParkStage 열거형과 같은 순서를 유지한다. */
    private static final List<StageContent> STAGES = List.of(TICKET, SNACK_SPLIT, PASS_BREAK_EVEN);

    private static final Map<String, StageContent> BY_STAGE_ID = STAGES.stream()
            .collect(java.util.stream.Collectors.toMap(
                    StageContent::stageId, content -> content, (a, b) -> a, LinkedHashMap::new));

    private static final Map<String, StageContent> BY_SCENARIO_ID = STAGES.stream()
            .collect(java.util.stream.Collectors.toMap(
                    StageContent::scenarioId, content -> content, (a, b) -> a, LinkedHashMap::new));

    public static List<StageContent> stages() {
        return STAGES;
    }

    public static List<String> stageOrder() {
        return STAGES.stream().map(StageContent::stageId).toList();
    }

    public static StageContent stage(String stageId) {
        StageContent content = BY_STAGE_ID.get(stageId);
        if (content == null) {
            throw new IllegalArgumentException("unknown amusement park stage: " + stageId);
        }
        return content;
    }

    /** 시나리오 id 로 스테이지를 찾는다. 없으면 null 을 돌려주고 4xx 판단은 호출부가 한다. */
    public static StageContent stageByScenarioId(String scenarioId) {
        return BY_SCENARIO_ID.get(scenarioId);
    }

    /**
     * 방문 시작 시 고정할 숫자 묶음. 키는 세 스테이지를 통틀어 겹치지 않으므로 한 장으로 담는다.
     */
    public static Map<String, Integer> initialFacts() {
        Map<String, Integer> facts = new LinkedHashMap<>();
        for (StageContent content : STAGES) {
            for (Fact fact : content.facts()) {
                facts.put(fact.key(), fact.value());
            }
        }
        return facts;
    }

    /**
     * 방문에 고정된 값으로 정답을 계산한다. 프런트나 AI가 보낸 값은 판정 근거가 되지 않는다.
     *
     * @param visitFacts 방문 행에 저장된 주어진 값
     * @return 아이가 구해야 하는 값의 정답. derivedKeys 와 키가 같다.
     */
    public static Map<String, Integer> expectedAnswers(String stageId, Map<String, Integer> visitFacts) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        switch (stageId) {
            case "ticket" -> expected.put(
                    "total_price", require(visitFacts, "ticket_price") * require(visitFacts, "party_count"));
            case "snack_split" -> {
                int total = require(visitFacts, "snack_total");
                int payers = require(visitFacts, "payer_count");
                if (payers <= 0 || total % payers != 0) {
                    throw new IllegalStateException(
                            "snack_split facts must divide evenly: %d / %d".formatted(total, payers));
                }
                expected.put("per_person", total / payers);
            }
            case "pass_break_even" -> {
                int single = require(visitFacts, "single_ride_price");
                int pass = require(visitFacts, "day_pass_price");
                if (single <= 0 || pass % single != 0) {
                    throw new IllegalStateException(
                            "pass_break_even facts must divide evenly: %d / %d".formatted(pass, single));
                }
                // 본전이 되는 횟수와, 그 다음 한 번부터 생기는 이득을 나눠 담는다.
                int breakEven = pass / single;
                expected.put("break_even_rides", breakEven);
                expected.put("benefit_from_rides", breakEven + 1);
            }
            default -> throw new IllegalArgumentException("unknown amusement park stage: " + stageId);
        }
        return expected;
    }

    /** 이슈의 verified_facts 한 장. 주어진 값과 정답을 합쳐 돌려준다. */
    public static Map<String, Integer> verifiedFacts(String stageId, Map<String, Integer> visitFacts) {
        Map<String, Integer> verified = new LinkedHashMap<>();
        for (String key : stage(stageId).factKeys()) {
            verified.put(key, require(visitFacts, key));
        }
        verified.putAll(expectedAnswers(stageId, visitFacts));
        return verified;
    }

    private static int require(Map<String, Integer> facts, String key) {
        Integer value = facts.get(key);
        if (value == null) {
            throw new IllegalStateException("missing amusement park fact: " + key);
        }
        return value;
    }
}

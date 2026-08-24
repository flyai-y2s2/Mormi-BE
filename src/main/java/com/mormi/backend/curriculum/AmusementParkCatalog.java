package com.mormi.backend.curriculum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * 놀이동산 생활수학 3스테이지의 서버 소유 콘텐츠와 판정 규칙.
 *
 * <p>카페는 문제 본문과 메뉴판을 프런트 정적 커리큘럼이 갖고 있지만, 놀이동산은 화면 문구까지
 * 서버가 내려준다. 프런트가 정답·설명문·전이 문장을 임의로 만들지 못하게 하려는 계약이다.
 *
 * <p>가격과 인원은 방문 시작 시 {@link #initialFacts()} 가 뽑아 방문 행에 저장하고, 같은 방문
 * 안에서는 바뀌지 않는다. 판정({@link #expectedAnswers})과 전이 문장({@link #transfer})은 언제나
 * 방문에 저장된 값만 보므로, 숫자를 어떻게 뽑든 판정식은 손대지 않는다.
 */
public final class AmusementParkCatalog {

    private AmusementParkCatalog() {
    }

    public static final String THEME_ID = "amusement_park";

    /**
     * 화면·AI에 한 줄로 나가는 사실의 이름표. 주어진 값과 아이가 구할 값 모두 이 형태로 둔다.
     *
     * <p>값은 여기 없다. 숫자를 방문마다 뽑으므로 카탈로그가 기본값을 들고 있으면, 방문 값이
     * 비었을 때 화면은 기본값을 보여 주고 판정은 막히는 어긋남이 생긴다. 값은 방문 행에만 둔다.
     *
     * <p>파생값의 label·unit 도 여기서 관리한다. AI park_context.facts 는 주어진 값과 구한 값을
     * 같은 모양으로 요구하므로, 파생값 이름표를 프런트나 대화 계층이 지어내면 화면·AI가 서로
     * 다른 말로 같은 수를 부르게 된다.
     */
    public record Fact(String key, String label, String unit) {
    }

    /**
     * 배운 전략을 새 숫자에 다시 적용해 보는 전이 턴. 문장은 서버가 소유한다.
     *
     * <p>{@link #transfer(String, Map)} 가 방문 숫자에서 만들어 낸다. 카탈로그에 고정 문장으로
     * 두면 본문제가 우연히 같은 숫자로 뽑혔을 때 "새 숫자에 다시 적용"이 되지 않는다.
     */
    public record Transfer(String prompt, String equation, String conclusion) {
    }

    /**
     * 스테이지 한 칸의 콘텐츠.
     *
     * @param facts        화면에 주어지는 값. 방문에 고정 저장된다.
     * @param derivedFacts 아이가 직접 구해야 하는 값. 정오 판정 대상이고, 값은 서버가 계산한다.
     * @param skill        FE가 완료 화면 라벨을 고를 때 쓰는 고정 enum
     * @param skillLabel   AI가 아이에게 설명할 때 쓰는 한국어 개념 이름
     * @param prompt       화면에 표시하는 문제 지시문. AI 첫 대사는 {@code mormiPrompt}가 만든다.
     */
    public record StageContent(
            String stageId,
            String scenarioId,
            String title,
            String mission,
            String skill,
            String skillLabel,
            String strategy,
            String mormiMisconception,
            String prompt,
            List<Fact> facts,
            List<Fact> derivedFacts) {

        public List<String> factKeys() {
            return facts.stream().map(Fact::key).toList();
        }

        public List<String> derivedKeys() {
            return derivedFacts.stream().map(Fact::key).toList();
        }

        /**
         * 주어진 값 + 구한 값을 화면 순서 그대로. AI park_context.facts 가 이 순서를 그대로 쓴다.
         *
         * <p>구한 값의 <b>수</b>는 {@link AmusementParkCatalog#verifiedFacts} 가 방문 숫자에서
         * 계산하고, 여기서는 그 수에 붙는 이름표만 준다. 두 목록의 키가 같아야 하므로
         * {@link #requiredVerifiedFactKeys()} 도 이 목록에서 뽑는다.
         */
        public List<Fact> allFacts() {
            return java.util.stream.Stream.concat(facts.stream(), derivedFacts.stream()).toList();
        }

        /** 대화 완료 시 AI가 반드시 채워 보내야 하는 키. 주어진 값 + 구한 값이다. */
        public List<String> requiredVerifiedFactKeys() {
            return allFacts().stream().map(Fact::key).toList();
        }
    }

    private static final StageContent TICKET = new StageContent(
            "ticket",
            "amusement_ticket_multiply",
            "매표소",
            "우리 일행 표 사기",
            "multiply",
            "같은 값 여러 번 더하기",
            "같은 돈이 여러 번이면 곱하면 돼",
            "표가 여러 장이어도 한 장 값만 내면 되는 줄 알았어.",
            "1인 입장료와 일행 수를 이용해 총액을 설명해 주세요.",
            List.of(
                    new Fact("ticket_price", "1인 입장료", "원"),
                    new Fact("party_count", "우리 일행", "명")),
            List.of(new Fact("total_price", "우리 일행 표값", "원")));

    private static final StageContent SNACK_SPLIT = new StageContent(
            "snack_split",
            "amusement_snack_divide",
            "간식가게",
            "간식값 똑같이 나눠 내기",
            "divide",
            "똑같이 나누기",
            "여럿이 똑같이 나눠 내면 나누면 돼",
            "간식을 같이 먹어도 산 사람만 돈을 내는 줄 알았어.",
            "간식 전체 값과 나눠 낼 사람 수를 이용해 한 사람이 낼 돈을 설명해 주세요.",
            List.of(
                    new Fact("snack_total", "간식 전체 값", "원"),
                    new Fact("payer_count", "나눠 낼 사람", "명")),
            List.of(new Fact("per_person", "한 사람이 낼 돈", "원")));

    private static final StageContent PASS_BREAK_EVEN = new StageContent(
            "pass_break_even",
            "amusement_pass_compare",
            "자유이용권 창구",
            "자유이용권이 이득인지 따져보기",
            "compare",
            "값을 나누고 비교하기",
            "낱개 값을 계속 더해서 묶음 값과 견줘 보면 돼",
            "자유이용권이 더 비싸 보이니까 무조건 손해인 줄 알았어.",
            "1회 이용권 값과 자유이용권 값을 이용해 몇 번부터 자유이용권이 이득인지 설명해 주세요.",
            List.of(
                    new Fact("single_ride_price", "1회 이용권", "원"),
                    new Fact("day_pass_price", "자유이용권", "원")),
            List.of(
                    new Fact("break_even_rides", "본전이 되는 횟수", "번"),
                    new Fact("benefit_from_rides", "이득이 시작되는 횟수", "번")));

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

    // ── 출제 범위. 아동 생활수학 범위에서 천 원 단위 금액과 2~5명 인원만 쓴다. ──
    private static final int[] TICKET_PRICES = {2000, 3000, 4000, 5000};
    private static final int[] PARTY_COUNTS = {2, 3, 4, 5};
    private static final int[] SNACK_PER_PERSON = {1000, 2000, 3000};
    private static final int[] PAYER_COUNTS = {2, 3, 4, 5};
    private static final int[] SINGLE_RIDE_PRICES = {1000, 2000, 3000};
    private static final int[] BREAK_EVEN_RIDES = {3, 4, 5, 6};

    /** 전이 문장에서 한 칸 올릴 폭. 본문제와 반드시 다른 숫자가 되게 하는 장치다. */
    private static final int TRANSFER_PRICE_STEP = 1000;
    private static final int TRANSFER_COUNT_STEP = 1;

    /**
     * 방문 시작 시 고정할 숫자 묶음. 키는 세 스테이지를 통틀어 겹치지 않으므로 한 장으로 담는다.
     *
     * <p>방문마다 새로 뽑는다. 같은 문제를 반복하면 아이가 계산 대신 답을 외워 통과할 수 있다.
     */
    public static Map<String, Integer> initialFacts() {
        return initialFacts(ThreadLocalRandom.current());
    }

    /**
     * 숫자를 뽑는 실제 구현. 테스트가 시드를 고정해 재현할 수 있도록 난수원을 받는다.
     *
     * <p>제약이 있는 두 단계는 <b>답을 먼저 뽑고 문제를 곱셈으로 되돌린다.</b> 예를 들어
     * 간식값은 "1인당 2,000원 × 3명 = 6,000원" 순서로 만들기 때문에 나누어떨어지지 않는
     * 조합 자체가 나올 수 없다. 아무 값이나 뽑아 놓고 제약을 어기면 버리고 다시 뽑는 방식과
     * 달리 재시도 루프가 필요 없고, 뽑히는 값의 분포도 한눈에 보인다.
     */
    public static Map<String, Integer> initialFacts(RandomGenerator random) {
        Map<String, Integer> facts = new LinkedHashMap<>();

        // 매표소: 곱셈이라 제약이 없다. 두 값을 그대로 뽑는다.
        facts.put("ticket_price", pick(random, TICKET_PRICES));
        facts.put("party_count", pick(random, PARTY_COUNTS));

        // 간식가게: 1인당 낼 돈(정답)을 먼저 뽑아 전체 값을 되돌린다 → 항상 나누어떨어진다.
        int payerCount = pick(random, PAYER_COUNTS);
        facts.put("snack_total", pick(random, SNACK_PER_PERSON) * payerCount);
        facts.put("payer_count", payerCount);

        // 자유이용권: 본전 횟수(정답)를 먼저 뽑아 이용권 값을 되돌린다 → 본전이 항상 정수다.
        int singleRidePrice = pick(random, SINGLE_RIDE_PRICES);
        facts.put("single_ride_price", singleRidePrice);
        facts.put("day_pass_price", singleRidePrice * pick(random, BREAK_EVEN_RIDES));

        requireSolvable(facts);
        return facts;
    }

    /**
     * 뽑은 숫자로 세 단계가 모두 풀리는지 확인한다. 하나라도 어긋나면 출제 자체를 막는다.
     *
     * <p>지금 구조에서는 깨질 수 없지만, 나중에 범위를 손대다 제약을 깨뜨리면 아이가 풀 수
     * 없는 방문이 DB에 저장된 뒤 제출 시점에 터진다. 그 전에 여기서 막는다.
     */
    private static void requireSolvable(Map<String, Integer> facts) {
        for (StageContent content : STAGES) {
            for (String key : content.factKeys()) {
                require(facts, key);
            }
            expectedAnswers(content.stageId(), facts);
        }
    }

    private static int pick(RandomGenerator random, int[] candidates) {
        return candidates[random.nextInt(candidates.length)];
    }

    /**
     * 방문에 고정된 값을 꺼낸다. 없으면 화면·AI에 엉뚱한 기본값을 보이지 않고 막는다.
     */
    public static int factValue(Map<String, Integer> visitFacts, String key) {
        return require(visitFacts, key);
    }

    /**
     * AI가 L4 첫 턴에 그대로 말하는 모르미 대사.
     *
     * <p>{@link StageContent#prompt()}는 화면용 존댓말 지시문이라 AI에 보내지 않는다. 첫 턴은
     * LLM을 거치지 않으므로 여기서 방문에 고정된 숫자를 넣고, 모르미 페르소나에 맞는 반말
     * 질문으로 완성한다.
     */
    public static String mormiPrompt(String stageId, Map<String, Integer> visitFacts) {
        return switch (stageId) {
            case "ticket" -> "표 한 장이 %s원이고 %d명이 가면 모두 얼마야?".formatted(
                    won(require(visitFacts, "ticket_price")),
                    require(visitFacts, "party_count"));
            case "snack_split" -> "%s원인 간식을 %d명이 똑같이 내려면 한 명은 얼마씩 내야 해?"
                    .formatted(
                            won(require(visitFacts, "snack_total")),
                            require(visitFacts, "payer_count"));
            case "pass_break_even" ->
                    "한 번에 %s원이고 자유이용권은 %s원이야. 몇 번부터 자유이용권이 더 이득일까?"
                            .formatted(
                                    won(require(visitFacts, "single_ride_price")),
                                    won(require(visitFacts, "day_pass_price")));
            default -> throw new IllegalArgumentException("unknown amusement park stage: " + stageId);
        };
    }

    /**
     * 이 방문의 전이 문장. 방문 숫자에서 한 칸 올린 <b>새 숫자</b>로 만든다.
     *
     * <p>배운 전략을 다른 수에 다시 적용해 보는 턴이라 본문제와 숫자가 같으면 안 된다.
     * 방문 facts 만 보고 계산하므로 따로 저장하지 않아도 같은 방문에서는 늘 같은 문장이 나온다.
     */
    public static Transfer transfer(String stageId, Map<String, Integer> visitFacts) {
        switch (stageId) {
            case "ticket" -> {
                int price = require(visitFacts, "ticket_price") + TRANSFER_PRICE_STEP;
                int count = require(visitFacts, "party_count") + TRANSFER_COUNT_STEP;
                int total = price * count;
                return new Transfer(
                        "그럼 1인 %s원이고 %d명이면?".formatted(won(price), count),
                        "%s × %d = %s".formatted(won(price), count, won(total)),
                        "%s원을 %d번 더한 것과 같으니까 %s원이야!".formatted(won(price), count, won(total)));
            }
            case "snack_split" -> {
                int perPerson = expected(stageId, visitFacts, "per_person") + TRANSFER_PRICE_STEP;
                int payers = require(visitFacts, "payer_count") + TRANSFER_COUNT_STEP;
                int total = perPerson * payers;
                return new Transfer(
                        "그럼 %s원을 %d명이 나눠 내면?".formatted(won(total), payers),
                        "%s ÷ %d = %s".formatted(won(total), payers, won(perPerson)),
                        "%s원을 똑같이 %d묶음으로 나눴으니까 한 사람은 %s원이야!"
                                .formatted(won(total), payers, won(perPerson)));
            }
            case "pass_break_even" -> {
                int single = require(visitFacts, "single_ride_price") + TRANSFER_PRICE_STEP;
                int breakEven = expected(stageId, visitFacts, "break_even_rides") + TRANSFER_COUNT_STEP;
                int pass = single * breakEven;
                return new Transfer(
                        "그럼 1회 %s원이고 자유이용권이 %s원이면?".formatted(won(single), won(pass)),
                        "%s ÷ %s = %d".formatted(won(pass), won(single), breakEven),
                        "%d번 타면 본전이고, %d번째부터는 자유이용권이 이득이야!"
                                .formatted(breakEven, breakEven + 1));
            }
            default -> throw new IllegalArgumentException("unknown amusement park stage: " + stageId);
        }
    }

    /** 전이 숫자도 본문제와 같은 판정식에서 출발하도록 정답을 되쓴다. */
    private static int expected(String stageId, Map<String, Integer> visitFacts, String key) {
        Integer value = expectedAnswers(stageId, visitFacts).get(key);
        if (value == null) {
            throw new IllegalStateException("missing amusement park expected answer: " + key);
        }
        return value;
    }

    /** 아이가 읽는 금액이라 천 단위 쉼표를 넣어 문장에 그대로 쓴다. */
    private static String won(int amount) {
        return "%,d".formatted(amount);
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

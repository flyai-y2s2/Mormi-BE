package com.mormi.backend.curriculum;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 서버가 소유하는 학습 규칙. 프런트가 보낸 보상액·해금 여부·가격을 신뢰하지 않는다.
 * 문제 본문과 보기는 여전히 프런트 정적 커리큘럼에 있고, 여기에는 판정에 필요한 값만 둔다.
 */
public final class CurriculumCatalog {

    private CurriculumCatalog() {
    }

    /** 반복학습 문제 수. math-curriculum.ts 의 masteryTarget 과 같다. */
    public static final int MASTERY_TARGET = 5;

    /** 세션 전체 상한 8분. MoramiApp.tsx 의 480초 타이머와 같다. */
    public static final int SESSION_TIME_LIMIT_SECONDS = 480;

    /** 지갑 시작 잔액. */
    public static final int WALLET_SEED = 6000;

    /** 모르미 가르치기 성공 고정 보상. */
    public static final int TEACH_REWARD = 500;

    /** 카페 실습용 고정 소지금. 지갑과 분리된 별도 화폐이며 거스름돈 기준액이다. */
    public static final int CAFE_TARGET_AMOUNT = 10000;

    /** 줄 서기 좌우 인원의 계약 범위. FE 화면·AI 컨텍스트가 같은 범위를 써야 한다. */
    public static final int CAFE_QUEUE_MIN_COUNT = 1;
    public static final int CAFE_QUEUE_MAX_COUNT = 5;

    /** 메뉴 고르기 예산. 프런트가 방문마다 이 중 하나를 뽑아 보내고, 서버는 목록에 있는 값만 받는다. */
    public static final Set<Integer> CAFE_MENU_BUDGETS = Set.of(7000, 8000);

    /**
     * 예전 배포에서 뽑혀 DB에 저장돼 있을 수 있는 예산. 새 대화에서는 쓰지 않지만,
     * 저장된 대화의 재진입·동기화가 깨지지 않도록 검증에서만 한시적으로 허용한다.
     */
    public static final Set<Integer> LEGACY_CAFE_MENU_BUDGETS = Set.of(9000, 10000);

    public static boolean isAllowedMenuBudget(int budget) {
        return CAFE_MENU_BUDGETS.contains(budget) || LEGACY_CAFE_MENU_BUDGETS.contains(budget);
    }

    /** 카페 해금에 필요한 5개 세션. journey-config.ts 의 cafeRequiredSessionIds 와 같다. */
    public static final List<String> CAFE_REQUIRED_SESSION_IDS =
            List.of("number-count", "number-compare", "money-count", "money-price", "money-budget");

    /**
     * 전체 반복학습 세션의 리포트 표시 이름.
     *
     * <p>카페 해금에 필요한 세션과 리포트에 포함할 세션은 서로 다른 개념이다. 리포트는
     * 완료된 전체 교육과정을 다루며, 선언 순서는 교사용 리포트의 기본 표시 순서로 사용한다.
     */
    public static final Map<String, String> SESSION_REPORT_LABELS = sessionReportLabels();

    public static final String THEME_CAFE = "cafe";

    private static Map<String, String> sessionReportLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("number-count", "수를 빠뜨리지 않고 세어요");
        labels.put("number-compare", "수의 크기를 비교해요");
        labels.put("number-make-ten", "10을 만들고 가르어요");
        labels.put("number-place-value", "십과 일을 나누어 봐요");
        labels.put("add-pictures", "그림을 모아요");
        labels.put("add-place", "자리끼리 더해요");
        labels.put("add-make-ten", "10을 먼저 만들어요");
        labels.put("sub-pictures", "남은 수를 찾아요");
        labels.put("sub-place", "자리끼리 빼요");
        labels.put("sub-borrow", "십 하나를 바꿔요");
        labels.put("multiply-groups", "가격과 개수를 곱해요");
        labels.put("multiply-addition", "같은 가격을 이어 더해요");
        labels.put("multiply-easy-tables", "여러 물건값과 예산을 비교해요");
        labels.put("multiply-tables", "곱셈구구의 관계를 찾아요");
        labels.put("divide-share", "물건값을 똑같이 나눠요");
        labels.put("divide-group", "예산으로 살 수 있는 개수를 찾아요");
        labels.put("money-count", "돈을 세어요");
        labels.put("money-price", "물건값을 더해요");
        labels.put("money-budget", "내 돈으로 살 수 있어요");
        labels.put("money-mission", "마트 심부름");
        labels.put("clock-basic", "정각과 30분");
        labels.put("clock-quarter", "15분과 45분");
        labels.put("time-duration", "걸린 시간을 찾아요");
        labels.put("time-calendar", "달력에서 날짜를 찾아요");
        labels.put("measure-compare", "길이를 직접 비교해요");
        labels.put("measure-ruler", "자로 길이를 재어요");
        labels.put("measure-weight-capacity", "무게와 들이를 비교해요");
        labels.put("geometry-shapes", "도형의 특징을 찾아요");
        labels.put("geometry-compose", "도형을 나누고 합쳐요");
        labels.put("geometry-position", "위치와 방향을 말해요");
        labels.put("pattern-repeat", "반복되는 규칙을 찾아요");
        labels.put("pattern-number", "수의 변화를 찾아요");
        labels.put("pattern-unknown", "빈칸의 수를 찾아요");
        labels.put("data-classify", "기준을 정해 분류해요");
        labels.put("data-chart", "표와 그래프를 읽어요");
        labels.put("data-chance", "가능성을 말해요");
        return Collections.unmodifiableMap(labels);
    }

    /** 카페 메뉴 고정 6종. CafeJourney.tsx 의 menu 와 가격이 같아야 한다. */
    public static final Map<String, Integer> CAFE_MENU_PRICES = Map.of(
            "americano", 3000,
            "milk", 2000,
            "strawberry-juice", 4000,
            "cookie", 2000,
            "strawberry-cake", 4500,
            "sandwich", 5000);

    public static final int CAFE_MENU_PICK_COUNT = 2;

    /** 결제에 쓸 수 있는 화폐. */
    public static final Set<Integer> PAYMENT_DENOMINATIONS = Set.of(100, 500, 1000, 5000);

    /** 거스름돈 구성에 쓸 수 있는 화폐. */
    public static final Set<Integer> CHANGE_DENOMINATIONS = Set.of(500, 1000);

    /**
     * 줄 서기 정답은 "사람이 더 적은 줄의 인원수".
     *
     * <p>좌우 인원은 프런트가 방문마다 새로 뽑으므로 정답 방향이 고정되지 않는다.
     * 아이가 고르는 값도 방향이 아니라 인원수라, 좌우 인원을 함께 받아 여기서 판정한다.
     */
    public static int queueCorrectCount(int leftCount, int rightCount) {
        return Math.min(leftCount, rightCount);
    }

    /**
     * 정답 전 오답 수에 따른 문제별 보상. 0개 200원 / 1개 150원 / 2개 100원 / 3개 이상 50원.
     * 5문제 전부 첫 시도 정답이면 1,000원이 상한이다.
     */
    public static int drillReward(int wrongCountBefore) {
        return switch (Math.max(0, wrongCountBefore)) {
            case 0 -> 200;
            case 1 -> 150;
            case 2 -> 100;
            default -> 50;
        };
    }

    public static int maxDrillReward() {
        return MASTERY_TARGET * drillReward(0);
    }

    public static boolean isCafeUnlocked(Set<String> completedSessionIds) {
        return completedSessionIds.containsAll(CAFE_REQUIRED_SESSION_IDS);
    }

    public static int orderTotal(List<String> menuIds) {
        return menuIds.stream()
                .map(id -> {
                    Integer price = CAFE_MENU_PRICES.get(id);
                    if (price == null) {
                        throw new IllegalArgumentException("unknown menu id: " + id);
                    }
                    return price;
                })
                .reduce(0, Integer::sum);
    }
}

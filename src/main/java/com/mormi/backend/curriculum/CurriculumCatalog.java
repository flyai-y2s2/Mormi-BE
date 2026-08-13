package com.mormi.backend.curriculum;

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

    /** 메뉴 고르기 예산. 프런트가 방문마다 이 중 하나를 뽑아 보내고, 서버는 목록에 있는 값만 받는다. */
    public static final Set<Integer> CAFE_MENU_BUDGETS = Set.of(8000, 9000, 10000);

    /** 카페 해금에 필요한 5개 세션. journey-config.ts 의 cafeRequiredSessionIds 와 같다. */
    public static final List<String> CAFE_REQUIRED_SESSION_IDS =
            List.of("number-count", "number-compare", "money-count", "money-price", "money-budget");

    public static final String THEME_CAFE = "cafe";

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

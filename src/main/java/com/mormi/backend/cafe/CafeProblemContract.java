package com.mormi.backend.cafe;

import com.mormi.backend.cafe.CafeDtos.CafeContext;
import com.mormi.backend.cafe.CafeDtos.CafeMenuItem;
import com.mormi.backend.common.ApiException;
import com.mormi.backend.curriculum.CurriculumCatalog;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FE·AI·BE가 함께 쓰는 카페 문제 사실의 공통 계약.
 *
 * <p>잘못된 문제 컨텍스트는 AI 대화를 끝까지 진행시킨 뒤 5xx로 무너뜨리지 않고,
 * 대화 시작·단계 제출 경계에서 안정적인 코드의 400으로 거절한다.
 */
public final class CafeProblemContract {

    private CafeProblemContract() {
    }

    /** 줄 인원은 계약 범위 안이어야 하고, 좌우가 같으면 "더 짧은 줄" 문제가 성립하지 않는다. */
    public static void requireQueueCounts(Integer leftCount, Integer rightCount) {
        requireQueueRange("left_count", leftCount);
        requireQueueRange("right_count", rightCount);
        if (leftCount.equals(rightCount)) {
            throw ApiException.badRequest(
                    "queue_count_equal", "좌우 줄 인원은 서로 달라야 합니다: " + leftCount);
        }
    }

    private static void requireQueueRange(String field, Integer count) {
        if (count == null
                || count < CurriculumCatalog.CAFE_QUEUE_MIN_COUNT
                || count > CurriculumCatalog.CAFE_QUEUE_MAX_COUNT) {
            throw ApiException.badRequest(
                    "queue_count_range",
                    "줄 인원(%s)은 %d~%d명이어야 합니다: %s".formatted(
                            field,
                            CurriculumCatalog.CAFE_QUEUE_MIN_COUNT,
                            CurriculumCatalog.CAFE_QUEUE_MAX_COUNT,
                            count));
        }
    }

    /** 메뉴판의 모든 ID·가격이 서버 카탈로그와 일치하고, 모르미 메뉴가 메뉴판 안에 있어야 한다. */
    public static void requireMenuBoard(CafeContext context) {
        Set<String> boardIds = new LinkedHashSet<>();
        for (CafeMenuItem item : context.menuItems()) {
            Integer catalogPrice = CurriculumCatalog.CAFE_MENU_PRICES.get(item.id());
            if (catalogPrice == null) {
                throw ApiException.badRequest(
                        "menu_unknown", "카탈로그에 없는 메뉴입니다: " + item.id());
            }
            if (!boardIds.add(item.id())) {
                throw ApiException.badRequest(
                        "menu_items_duplicate", "메뉴판에 같은 메뉴가 중복되었습니다: " + item.id());
            }
            if (!catalogPrice.equals(item.price())) {
                throw ApiException.badRequest(
                        "menu_price_mismatch",
                        "메뉴 가격이 서버 가격표와 다릅니다: %s (보낸 값 %s, 서버 %d)"
                                .formatted(item.id(), item.price(), catalogPrice));
            }
        }
        if (!boardIds.contains(context.mormiMenuId())) {
            throw ApiException.badRequest(
                    "mormi_menu_unknown", "모르미 메뉴가 메뉴판에 없습니다: " + context.mormiMenuId());
        }
    }

    /** 제출된 메뉴는 전부 카탈로그에 있어야 하고, 같은 메뉴를 두 번 고를 수 없다. */
    public static void requireKnownDistinctMenus(List<String> menuIds) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String menuId : menuIds) {
            if (!CurriculumCatalog.CAFE_MENU_PRICES.containsKey(menuId)) {
                throw ApiException.badRequest(
                        "menu_unknown", "카탈로그에 없는 메뉴입니다: " + menuId);
            }
            if (!distinct.add(menuId)) {
                throw ApiException.badRequest(
                        "menu_duplicate", "같은 메뉴는 하나만 고를 수 있습니다: " + menuId);
            }
        }
    }

    /** 예산은 화면이 뽑아 보내지만, 서버는 허용 목록(구버전 저장분 포함)에 있는 값만 인정한다. */
    public static int requireKnownBudget(Integer budget) {
        if (budget == null || !CurriculumCatalog.isAllowedMenuBudget(budget)) {
            throw ApiException.badRequest("budget", "사용할 수 없는 예산입니다: " + budget);
        }
        return budget;
    }
}

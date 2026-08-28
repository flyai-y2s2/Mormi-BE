package com.mormi.backend.cafe;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mormi.backend.cafe.CafeDtos.CafeContext;
import com.mormi.backend.cafe.CafeDtos.CafeMenuItem;
import com.mormi.backend.common.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class CafeProblemContractTest {

    @Test
    void queueCountsWithinRangeAndDistinctPass() {
        assertThatCode(() -> CafeProblemContract.requireQueueCounts(1, 5)).doesNotThrowAnyException();
        assertThatCode(() -> CafeProblemContract.requireQueueCounts(5, 1)).doesNotThrowAnyException();
        assertThatCode(() -> CafeProblemContract.requireQueueCounts(2, 3)).doesNotThrowAnyException();
    }

    @Test
    void queueCountsOutsideRangeAreRejected() {
        assertThatThrownBy(() -> CafeProblemContract.requireQueueCounts(0, 3))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "queue_count_range");
        assertThatThrownBy(() -> CafeProblemContract.requireQueueCounts(3, 6))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "queue_count_range");
        assertThatThrownBy(() -> CafeProblemContract.requireQueueCounts(null, 3))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "queue_count_range");
    }

    @Test
    void equalQueueCountsAreRejected() {
        assertThatThrownBy(() -> CafeProblemContract.requireQueueCounts(4, 4))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "queue_count_equal");
    }

    @Test
    void menuBoardMatchingTheCatalogPasses() {
        CafeContext context = new CafeContext(
                List.of(
                        new CafeMenuItem("americano", "아메리카노", 3000),
                        new CafeMenuItem("milk", "우유", 2000)),
                "americano",
                9000);

        assertThatCode(() -> CafeProblemContract.requireMenuBoard(context))
                .doesNotThrowAnyException();
    }

    @Test
    void menuBoardWithUnknownIdIsRejected() {
        CafeContext context = new CafeContext(
                List.of(new CafeMenuItem("latte", "라떼", 3000)), "latte", 9000);

        assertThatThrownBy(() -> CafeProblemContract.requireMenuBoard(context))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "menu_unknown");
    }

    @Test
    void menuBoardWithWrongPriceIsRejected() {
        CafeContext context = new CafeContext(
                List.of(new CafeMenuItem("americano", "아메리카노", 3500)), "americano", 9000);

        assertThatThrownBy(() -> CafeProblemContract.requireMenuBoard(context))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "menu_price_mismatch");
    }

    @Test
    void menuBoardWithDuplicateIdIsRejected() {
        CafeContext context = new CafeContext(
                List.of(
                        new CafeMenuItem("americano", "아메리카노", 3000),
                        new CafeMenuItem("americano", "아메리카노", 3000)),
                "americano",
                9000);

        assertThatThrownBy(() -> CafeProblemContract.requireMenuBoard(context))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "menu_items_duplicate");
    }

    @Test
    void mormiMenuMustBeOnTheBoard() {
        CafeContext context = new CafeContext(
                List.of(new CafeMenuItem("americano", "아메리카노", 3000)), "milk", 9000);

        assertThatThrownBy(() -> CafeProblemContract.requireMenuBoard(context))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "mormi_menu_unknown");
    }

    @Test
    void childMenuMustBeOnTheBoardAndDifferFromMormiMenu() {
        CafeContext valid = new CafeContext(
                List.of(
                        new CafeMenuItem("americano", "아메리카노", 3000),
                        new CafeMenuItem("milk", "우유", 2000)),
                "americano",
                "milk",
                null);
        assertThatCode(() -> CafeProblemContract.requireMenuBoard(valid))
                .doesNotThrowAnyException();

        CafeContext unknown = new CafeContext(
                List.of(
                        new CafeMenuItem("americano", "아메리카노", 3000),
                        new CafeMenuItem("milk", "우유", 2000)),
                "americano",
                "cookie",
                null);
        assertThatThrownBy(() -> CafeProblemContract.requireMenuBoard(unknown))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "child_menu_unknown");

        CafeContext duplicate = new CafeContext(
                List.of(
                        new CafeMenuItem("americano", "아메리카노", 3000),
                        new CafeMenuItem("milk", "우유", 2000)),
                "americano",
                "americano",
                null);
        assertThatThrownBy(() -> CafeProblemContract.requireMenuBoard(duplicate))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "menu_duplicate");
    }

    @Test
    void submittedMenusMustBeKnownAndDistinct() {
        assertThatCode(() -> CafeProblemContract.requireKnownDistinctMenus(
                List.of("americano", "cookie"))).doesNotThrowAnyException();

        assertThatThrownBy(() -> CafeProblemContract.requireKnownDistinctMenus(
                List.of("cookie", "cookie")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "menu_duplicate");

        assertThatThrownBy(() -> CafeProblemContract.requireKnownDistinctMenus(
                List.of("cookie", "latte")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "menu_unknown");
    }

    @Test
    void budgetMustBeOnTheAllowList() {
        assertThatCode(() -> CafeProblemContract.requireKnownBudget(7000))
                .doesNotThrowAnyException();
        assertThatCode(() -> CafeProblemContract.requireKnownBudget(8000))
                .doesNotThrowAnyException();
        // 배포 전 저장된 대화가 깨지지 않도록 구버전 예산은 한시 허용한다.
        assertThatCode(() -> CafeProblemContract.requireKnownBudget(9000))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> CafeProblemContract.requireKnownBudget(6000))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "budget");
        assertThatThrownBy(() -> CafeProblemContract.requireKnownBudget(null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "budget");
    }
}

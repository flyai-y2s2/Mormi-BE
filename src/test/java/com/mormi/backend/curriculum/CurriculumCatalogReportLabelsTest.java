package com.mormi.backend.curriculum;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CurriculumCatalogReportLabelsTest {

    @Test
    void reportLabelsMatchTheCurrentLearnerFacingLessonTitles() {
        assertThat(CurriculumCatalog.SESSION_REPORT_LABELS).isEqualTo(Map.ofEntries(
                Map.entry("number-count", "수를 빠뜨리지 않고 세어요"),
                Map.entry("number-compare", "수의 크기를 비교해요"),
                Map.entry("number-make-ten", "10을 만들고 가르어요"),
                Map.entry("number-place-value", "십과 일을 나누어 봐요"),
                Map.entry("add-pictures", "그림을 모아요"),
                Map.entry("add-place", "자리끼리 더해요"),
                Map.entry("add-make-ten", "10을 먼저 만들어요"),
                Map.entry("sub-pictures", "남은 수를 찾아요"),
                Map.entry("sub-place", "자리끼리 빼요"),
                Map.entry("sub-borrow", "십 하나를 바꿔요"),
                Map.entry("multiply-groups", "가격과 개수를 곱해요"),
                Map.entry("multiply-addition", "같은 가격을 이어 더해요"),
                Map.entry("multiply-easy-tables", "여러 물건값과 예산을 비교해요"),
                Map.entry("multiply-tables", "곱셈구구의 관계를 찾아요"),
                Map.entry("divide-share", "물건값을 똑같이 나눠요"),
                Map.entry("divide-group", "예산으로 살 수 있는 개수를 찾아요"),
                Map.entry("money-count", "돈을 세어요"),
                Map.entry("money-price", "물건값을 더해요"),
                Map.entry("money-budget", "내 돈으로 살 수 있어요"),
                Map.entry("money-mission", "마트 심부름"),
                Map.entry("clock-basic", "정각과 30분"),
                Map.entry("clock-quarter", "15분과 45분"),
                Map.entry("time-duration", "걸린 시간을 찾아요"),
                Map.entry("time-calendar", "달력에서 날짜를 찾아요"),
                Map.entry("measure-compare", "길이를 직접 비교해요"),
                Map.entry("measure-ruler", "자로 길이를 재어요"),
                Map.entry("measure-weight-capacity", "무게와 들이를 비교해요"),
                Map.entry("geometry-shapes", "도형의 특징을 찾아요"),
                Map.entry("geometry-compose", "도형을 나누고 합쳐요"),
                Map.entry("geometry-position", "위치와 방향을 말해요"),
                Map.entry("pattern-repeat", "반복되는 규칙을 찾아요"),
                Map.entry("pattern-number", "수의 변화를 찾아요"),
                Map.entry("pattern-unknown", "빈칸의 수를 찾아요"),
                Map.entry("data-classify", "기준을 정해 분류해요"),
                Map.entry("data-chart", "표와 그래프를 읽어요"),
                Map.entry("data-chance", "가능성을 말해요")));
    }
}

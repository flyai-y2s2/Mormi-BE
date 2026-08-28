package com.mormi.backend.cafe;

import java.util.List;

/**
 * 카페 진행 단계. 돌다리 잠금 해제는 서버가 이 순서로 판정한다.
 * 프런트의 CafeStep("overview"/"sum"/"done")은 표시용이고, 저장 값은 이쪽을 기준으로 한다.
 */
public enum CafeStage {

    QUEUE("queue"),
    MENU("menu"),
    CALCULATE("calculate"),
    CHANGE("change"),
    COMPLETE("complete");

    private static final List<CafeStage> ORDER =
            List.of(QUEUE, MENU, CALCULATE, CHANGE, COMPLETE);

    private final String value;

    CafeStage(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static CafeStage from(String value) {
        return ORDER.stream()
                .filter(stage -> stage.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown cafe stage: " + value));
    }

    public CafeStage next() {
        // 메뉴 선택은 2단계 합산 문제를 구성하는 화면 준비 동작이지 별도 학습
        // 스테이지가 아니다. 신규 방문은 줄 서기에서 바로 합산으로 간다.
        if (this == QUEUE || this == MENU) {
            return CALCULATE;
        }
        int index = ORDER.indexOf(this);
        return index >= ORDER.size() - 1 ? COMPLETE : ORDER.get(index + 1);
    }

    /** 아직 도달하지 않은 단계를 건너뛰어 제출하는 것을 막는다. */
    public boolean isReachedBy(CafeStage current) {
        // 옛 배포에서 MENU에 멈춘 방문도 새 3단계 흐름의 합산을 바로 열 수 있다.
        if (this == CALCULATE && current == MENU) {
            return true;
        }
        return ORDER.indexOf(current) >= ORDER.indexOf(this);
    }
}

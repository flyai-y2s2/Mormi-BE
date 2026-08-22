package com.mormi.backend.amusementpark;

import java.util.List;

/**
 * 놀이동산 진행 단계. 해금 판정은 서버가 이 순서로만 한다.
 *
 * <p>카페의 CafeStage 와 같은 규칙을 쓴다. 앞으로만 전진하고, 아직 도달하지 않은 단계는
 * 제출도 대화 시작도 막는다.
 */
public enum AmusementParkStage {

    TICKET("ticket"),
    SNACK_SPLIT("snack_split"),
    PASS_BREAK_EVEN("pass_break_even"),
    COMPLETE("complete");

    private static final List<AmusementParkStage> ORDER =
            List.of(TICKET, SNACK_SPLIT, PASS_BREAK_EVEN, COMPLETE);

    /** 아이가 실제로 푸는 세 칸. COMPLETE 는 진행도 표시에서 제외한다. */
    private static final List<AmusementParkStage> PLAYABLE =
            List.of(TICKET, SNACK_SPLIT, PASS_BREAK_EVEN);

    private final String value;

    AmusementParkStage(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static List<AmusementParkStage> playable() {
        return PLAYABLE;
    }

    public static AmusementParkStage from(String value) {
        return ORDER.stream()
                .filter(stage -> stage.value.equals(value))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException("unknown amusement park stage: " + value));
    }

    public AmusementParkStage next() {
        int index = ORDER.indexOf(this);
        return index >= ORDER.size() - 1 ? COMPLETE : ORDER.get(index + 1);
    }

    /** 아직 도달하지 않은 단계를 건너뛰어 제출하는 것을 막는다. */
    public boolean isReachedBy(AmusementParkStage current) {
        return ORDER.indexOf(current) >= ORDER.indexOf(this);
    }

    /** 이미 지나온 단계인지. 방문 조회의 stage_progress 를 만들 때 쓴다. */
    public boolean isClearedBy(AmusementParkStage current) {
        return ORDER.indexOf(current) > ORDER.indexOf(this);
    }
}

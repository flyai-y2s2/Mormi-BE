package com.mormi.backend.reward;

public enum RewardSource {

    /** 온보딩 시 1회 지급되는 시작 잔액. */
    SEED("seed"),

    /** 반복학습 문제별 보상. */
    DRILL("drill"),

    /** 모르미 가르치기 성공 고정 보상. */
    TEACH("teach");

    private final String value;

    RewardSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

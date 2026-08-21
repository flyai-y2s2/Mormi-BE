package com.mormi.backend.common;

import java.util.List;
import java.util.Locale;

/** 발화사다리의 활성 단계와 과거 wire 값 호환 규칙을 한곳에서 관리한다. */
public final class ExpressionLevels {

    /** 낮은 인덱스일수록 지원이 크다. L1은 더 이상 활성 단계가 아니다. */
    public static final List<String> ACTIVE_SUPPORT_ORDER = List.of("L0", "L2", "L3", "L4");

    private ExpressionLevels() {
    }

    /**
     * 저장된 과거 L1을 현재 선택지 단계 L2로 해석한다.
     *
     * <p>이 함수는 조회·집계 경계에서만 사용한다. 원본 관찰 행은 수정하지 않는다.
     */
    public static String canonicalForRead(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("L1".equals(normalized)) {
            return "L2";
        }
        return ACTIVE_SUPPORT_ORDER.contains(normalized) ? normalized : value;
    }

    /** 계약에 속하는 활성 단계만 반환한다. 과거 L1은 L2로 정규화된다. */
    public static String activeOrNull(String value) {
        String canonical = canonicalForRead(value);
        return ACTIVE_SUPPORT_ORDER.contains(canonical) ? canonical : null;
    }
}

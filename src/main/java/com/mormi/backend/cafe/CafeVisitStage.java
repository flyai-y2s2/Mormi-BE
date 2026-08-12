package com.mormi.backend.cafe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 카페 스테이지 시도 1건. 틀린 시도도 남긴다.
 *
 * <p>payload 에는 화폐별 개수, 고른 메뉴 id 같은 최종 구성만 넣는다.
 * −/＋ 버튼 클릭 원본 로그는 저장하지 않는다.
 */
@Entity
@Table(name = "cafe_visit_stages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CafeVisitStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cafe_visit_id", nullable = false, updatable = false)
    private Long cafeVisitId;

    @Column(name = "stage", nullable = false, length = 20, updatable = false)
    private String stage;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @Column(name = "elapsed_ms")
    private Integer elapsedMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    private CafeVisitStage(
            Long cafeVisitId,
            CafeStage stage,
            int attemptNo,
            boolean correct,
            Integer elapsedMs,
            Map<String, Object> payload) {
        this.cafeVisitId = cafeVisitId;
        this.stage = stage.value();
        this.attemptNo = attemptNo;
        this.correct = correct;
        this.elapsedMs = elapsedMs;
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    public static CafeVisitStage record(
            Long cafeVisitId,
            CafeStage stage,
            int attemptNo,
            boolean correct,
            Integer elapsedMs,
            Map<String, Object> payload) {
        return new CafeVisitStage(cafeVisitId, stage, attemptNo, correct, elapsedMs, payload);
    }
}

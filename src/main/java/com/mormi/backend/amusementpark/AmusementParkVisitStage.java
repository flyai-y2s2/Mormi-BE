package com.mormi.backend.amusementpark;

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
 * 놀이동산 스테이지 시도 1건. 틀린 시도도 남긴다.
 *
 * <p>payload 에는 아이가 구한 값과 서버가 계산한 정답 같은 구조 데이터만 넣는다.
 * 자유 발화 원문은 담지 않는다.
 */
@Entity
@Table(name = "amusement_park_visit_stages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AmusementParkVisitStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "park_visit_id", nullable = false, updatable = false)
    private Long parkVisitId;

    @Column(name = "stage", nullable = false, length = 30, updatable = false)
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

    private AmusementParkVisitStage(
            Long parkVisitId,
            AmusementParkStage stage,
            int attemptNo,
            boolean correct,
            Integer elapsedMs,
            Map<String, Object> payload) {
        this.parkVisitId = parkVisitId;
        this.stage = stage.value();
        this.attemptNo = attemptNo;
        this.correct = correct;
        this.elapsedMs = elapsedMs;
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    public static AmusementParkVisitStage record(
            Long parkVisitId,
            AmusementParkStage stage,
            int attemptNo,
            boolean correct,
            Integer elapsedMs,
            Map<String, Object> payload) {
        return new AmusementParkVisitStage(parkVisitId, stage, attemptNo, correct, elapsedMs, payload);
    }
}

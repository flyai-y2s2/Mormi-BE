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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 놀이동산 방문 한 회. 새로고침해도 이 진행 상태로 복구된다.
 *
 * <p>문제 스냅샷은 Mormi-AI 대화 상태에 고정된다. 이 엔티티는 방문·해금·현재 단계만 소유한다.
 * {@code facts} 컬럼은 운영 데이터와의 무중단 호환을 위해 남겨 둔 폐기 예정 컬럼이며 새 방문은
 * 빈 객체를 저장한다.
 */
@Entity
@Table(name = "amusement_park_visits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AmusementParkVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, length = 60, updatable = false)
    private String publicId;

    @Column(name = "learner_id", nullable = false, updatable = false)
    private Long learnerId;

    @Column(name = "stage", nullable = false, length = 30)
    private String stage;

    /** @deprecated 문제 원장은 Mormi-AI다. DB 마이그레이션 전까지 빈 객체로만 유지한다. */
    @Deprecated
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "facts", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Integer> facts = new LinkedHashMap<>();

    @Column(name = "started_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    @Setter
    private OffsetDateTime completedAt;

    private AmusementParkVisit(Long learnerId) {
        this.publicId = "park_visit_" + UUID.randomUUID().toString().replace("-", "");
        this.learnerId = learnerId;
        this.stage = AmusementParkStage.TICKET.value();
    }

    public static AmusementParkVisit start(Long learnerId) {
        return new AmusementParkVisit(learnerId);
    }

    public AmusementParkStage stage() {
        return AmusementParkStage.from(stage);
    }

    /** 뒤로 되돌리지 않고 앞으로만 진행시킨다. */
    public void advanceTo(AmusementParkStage next) {
        if (next.isReachedBy(stage())) {
            return;
        }
        this.stage = next.value();
        if (next == AmusementParkStage.COMPLETE && completedAt == null) {
            this.completedAt = OffsetDateTime.now();
        }
    }

    public boolean isCompleted() {
        return completedAt != null;
    }
}

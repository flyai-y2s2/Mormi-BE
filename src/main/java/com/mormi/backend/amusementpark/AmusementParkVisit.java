package com.mormi.backend.amusementpark;

import com.mormi.backend.curriculum.AmusementParkCatalog;
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
 * <p>카페와 다르게 문제 숫자(가격·인원)를 방문 행에 고정 저장한다. 이슈 계약이
 * "방문을 시작할 때 가격과 인원을 고정하고 같은 visit_id 안에서는 바꾸지 않는다"를 요구하므로,
 * 프런트가 요청마다 숫자를 보내는 카페 방식 대신 서버가 방문 시작 시 한 번만 정한다.
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

    /** 방문 시작 시 고정된 주어진 값. ticket_price, party_count 처럼 스테이지를 통틀어 유일한 키다. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "facts", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Integer> facts = new LinkedHashMap<>();

    @Column(name = "started_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    @Setter
    private OffsetDateTime completedAt;

    private AmusementParkVisit(Long learnerId, Map<String, Integer> facts) {
        this.publicId = "park_visit_" + UUID.randomUUID().toString().replace("-", "");
        this.learnerId = learnerId;
        this.stage = AmusementParkStage.TICKET.value();
        this.facts = new LinkedHashMap<>(facts);
    }

    public static AmusementParkVisit start(Long learnerId) {
        return new AmusementParkVisit(learnerId, AmusementParkCatalog.initialFacts());
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

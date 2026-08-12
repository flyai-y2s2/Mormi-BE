package com.mormi.backend.cafe;

import com.mormi.backend.curriculum.CurriculumCatalog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 카페 외출 한 회. 새로고침해도 이 진행 상태로 복구된다. */
@Entity
@Table(name = "cafe_visits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CafeVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, length = 60, updatable = false)
    private String publicId;

    @Column(name = "learner_id", nullable = false, updatable = false)
    private Long learnerId;

    @Column(name = "stage", nullable = false, length = 20)
    private String stage;

    @Column(name = "target_amount", nullable = false)
    private int targetAmount;

    @Column(name = "order_total")
    @Setter
    private Integer orderTotal;

    @Column(name = "paid_amount")
    @Setter
    private Integer paidAmount;

    @Column(name = "change_amount")
    @Setter
    private Integer changeAmount;

    @Column(name = "started_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    @Setter
    private OffsetDateTime completedAt;

    private CafeVisit(Long learnerId) {
        this.publicId = "visit_" + UUID.randomUUID().toString().replace("-", "");
        this.learnerId = learnerId;
        this.stage = CafeStage.QUEUE.value();
        this.targetAmount = CurriculumCatalog.CAFE_TARGET_AMOUNT;
    }

    public static CafeVisit start(Long learnerId) {
        return new CafeVisit(learnerId);
    }

    public CafeStage stage() {
        return CafeStage.from(stage);
    }

    /** 뒤로 되돌리지 않고 앞으로만 진행시킨다. */
    public void advanceTo(CafeStage next) {
        if (next.isReachedBy(stage())) {
            return;
        }
        this.stage = next.value();
        if (next == CafeStage.COMPLETE && completedAt == null) {
            this.completedAt = OffsetDateTime.now();
        }
    }

    public boolean isCompleted() {
        return completedAt != null;
    }
}

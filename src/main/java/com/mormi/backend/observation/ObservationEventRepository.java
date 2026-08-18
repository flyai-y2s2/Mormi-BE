package com.mormi.backend.observation;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ObservationEventRepository extends JpaRepository<ObservationEvent, Long> {

    /**
     * 이벤트를 없을 때만 넣고, 실제로 넣었으면 1을 돌려준다.
     *
     * <p>'조회 후 없으면 삽입'은 동시 요청 두 개가 모두 '없음'을 보고 둘 다 삽입에 도달할 수 있다.
     * ON CONFLICT DO NOTHING 은 경쟁 조건에서도 예외 없이 한 행만 남긴다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO observation_events (event_id, schema_version, event_type, payload)
            VALUES (:eventId, :schemaVersion, :eventType, CAST(:payload AS jsonb))
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("schemaVersion") String schemaVersion,
            @Param("eventType") String eventType,
            @Param("payload") String payload);

    /** 같은 이벤트가 동시에 재전송돼도 나란히 재처리하지 않도록 행 잠금으로 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ObservationEvent e WHERE e.eventId = :eventId")
    Optional<ObservationEvent> findByEventIdForUpdate(@Param("eventId") String eventId);

    Optional<ObservationEvent> findByEventId(String eventId);
}

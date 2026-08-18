package com.mormi.backend.observation;

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
 * AI 관찰 이벤트 수신함 1행. 받은 이벤트를 그대로 보관한다.
 *
 * <p>파싱에 실패한 이벤트도 payload 원본과 오류를 남겨 재전송·재처리를 허용한다.
 * 아이 원문 발화와 암호화 키는 이벤트 계약상 수신하지 않는다.
 */
@Entity
@Table(name = "observation_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObservationEvent {

    public static final String STATUS_RECEIVED = "received";
    public static final String STATUS_PROCESSED = "processed";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** AI가 부여하는 멱등 키. 같은 이벤트가 몇 번 와도 이 값으로 한 행만 남는다. */
    @Column(name = "event_id", nullable = false, length = 100, updatable = false)
    private String eventId;

    @Column(name = "schema_version", nullable = false, length = 20, updatable = false)
    private String schemaVersion;

    @Column(name = "event_type", nullable = false, length = 40, updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> payload = new LinkedHashMap<>();

    /** received | processed | failed */
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_RECEIVED;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "received_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    public void markProcessed() {
        this.status = STATUS_PROCESSED;
        this.errorMessage = null;
        this.processedAt = OffsetDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = STATUS_FAILED;
        this.errorMessage = errorMessage;
        this.processedAt = OffsetDateTime.now();
    }
}

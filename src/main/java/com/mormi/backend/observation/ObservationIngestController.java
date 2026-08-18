package com.mormi.backend.observation;

import com.mormi.backend.common.ApiException;
import com.mormi.backend.observation.ObservationDtos.IngestObservationRequest;
import com.mormi.backend.observation.ObservationDtos.IngestObservationResponse;
import jakarta.validation.Valid;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI가 관찰 이벤트를 보내는 내부 전용 경로.
 *
 * <p>브라우저가 부르는 /v1/** 과 분리해 학습자 토큰이 아니라 서비스 키로 막는다.
 * 재전송은 오류가 아니라 정상 200 이며 duplicate 로 알려 준다.
 */
@RestController
@RequestMapping("/internal/v1/observations")
public class ObservationIngestController {

    /** 잠시 뒤 다시 보내면 성공할 수 있는 거절 사유. 나머지는 다시 보내도 같은 결과다. */
    private static final Set<String> RETRYABLE = Set.of("unknown_conversation");

    private final ObservationIngestService observationIngestService;

    public ObservationIngestController(ObservationIngestService observationIngestService) {
        this.observationIngestService = observationIngestService;
    }

    @PostMapping("/events")
    public IngestObservationResponse ingest(@Valid @RequestBody IngestObservationRequest request) {
        IngestObservationResponse response = observationIngestService.ingest(request);
        if (response.rejectionCode() == null) {
            return response;
        }
        // 서비스 트랜잭션은 이미 커밋됐다. 여기서 예외를 던져도 failed 기록은 남아 재처리할 수 있다.
        if (RETRYABLE.contains(response.rejectionCode())) {
            throw ApiException.conflict(response.rejectionCode(), response.rejectionMessage());
        }
        throw ApiException.unprocessable(response.rejectionCode(), response.rejectionMessage());
    }
}

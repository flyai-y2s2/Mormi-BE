package com.mormi.backend.observation;

/**
 * 이벤트를 관찰값으로 반영할 수 없을 때 던진다.
 *
 * <p>ApiException 을 바로 던지지 않는 이유는, 이 예외를 서비스가 잡아서
 * 이벤트를 failed 로 기록하고 트랜잭션을 커밋해야 하기 때문이다.
 * 그래야 실패한 이벤트가 수신함에 남아 나중에 재처리할 수 있다.
 */
public class ObservationRejectedException extends RuntimeException {

    private final String code;

    public ObservationRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

package com.mormi.backend.report;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mormi.backend.common.ApiException;
import org.junit.jupiter.api.Test;

class LocalReportAdminGuardTest {

    @Test
    void acceptsOnlyLoopbackWithTheConfiguredKey() {
        var guard = new LocalReportAdminGuard("local-secret");

        assertThatCode(() -> guard.requireAllowed("local-secret", "127.0.0.1"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireAllowed("wrong", "127.0.0.1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.requireAllowed("local-secret", "10.0.0.8"))
                .isInstanceOf(ApiException.class);
    }
}

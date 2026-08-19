package com.mormi.backend.report;

import com.mormi.backend.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("local")
@Component
@ConditionalOnProperty(name = "mormi.local-report-admin.enabled", havingValue = "true")
public final class LocalReportAdminGuard {

    private final byte[] expectedKey;

    public LocalReportAdminGuard(@Value("${mormi.local-report-admin.key:}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Local report admin key is required");
        }
        this.expectedKey = key.getBytes(StandardCharsets.UTF_8);
    }

    void requireAllowed(String providedKey, String remoteAddress) {
        boolean loopback = "127.0.0.1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress)
                || "::1".equals(remoteAddress);
        byte[] provided = providedKey == null ? new byte[0] : providedKey.getBytes(StandardCharsets.UTF_8);
        if (!loopback || !MessageDigest.isEqual(expectedKey, provided)) {
            throw ApiException.forbidden("로컬 리포트 관리자 권한이 없습니다.");
        }
    }
}

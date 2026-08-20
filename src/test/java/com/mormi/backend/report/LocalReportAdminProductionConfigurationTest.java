package com.mormi.backend.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalReportAdminProductionConfigurationTest {

    @Test
    void enablesTheServerKeyProtectedReportEndpointOutsideTheLocalProfile() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues(
                        "mormi.local-report-admin.enabled=true",
                        "mormi.local-report-admin.key=server-secret")
                .withBean(LocalReportAdminService.class, () -> mock(LocalReportAdminService.class))
                .withBean(DiagnosticReportService.class, () -> mock(DiagnosticReportService.class))
                .withBean(LocalReportLoginAttemptLimiter.class, () -> mock(LocalReportLoginAttemptLimiter.class))
                .withUserConfiguration(LocalReportAdminGuard.class, LocalReportAdminController.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(LocalReportAdminGuard.class);
                    assertThat(context).hasSingleBean(LocalReportAdminController.class);
                });
    }
}

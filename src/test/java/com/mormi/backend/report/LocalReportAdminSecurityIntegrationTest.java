package com.mormi.backend.report;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.auth.AuthService;
import com.mormi.backend.auth.AuthTokenFilter;
import com.mormi.backend.auth.InternalServiceKeyFilter;
import com.mormi.backend.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = LocalReportAdminController.class, properties = {
        "mormi.local-report-admin.enabled=true",
        "mormi.local-report-admin.key=local-secret"
})
@Import({SecurityConfig.class, LocalReportAdminGuard.class, AuthTokenFilter.class, InternalServiceKeyFilter.class})
class LocalReportAdminSecurityIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AuthService authService;

    @MockitoBean
    LocalReportAdminService localReportAdminService;

    @MockitoBean
    DiagnosticReportService diagnosticReportService;

    @MockitoBean
    LocalReportLoginAttemptLimiter loginAttemptLimiter;

    @Test
    void reportAdminCanReachLadderApprovalWithoutALearnerToken() throws Exception {
        mockMvc.perform(post("/v1/local-report-admin/learners/19/ladder-recommendations/ladder-1/approve")
                        .header("X-Mormi-Local-Admin-Key", "local-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());
    }
}

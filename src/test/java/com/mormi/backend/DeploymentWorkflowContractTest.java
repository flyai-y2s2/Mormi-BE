package com.mormi.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeploymentWorkflowContractTest {

    @Test
    void deploymentPersistsAndInjectsAHiddenObservationIngestKey() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/deploy.yml"));

        assertThat(workflow).contains("MORMI_OBSERVATION_INGEST_KEY");
        assertThat(workflow).contains("secrets.MORMI_OBSERVATION_INGEST_KEY");
        assertThat(workflow).contains("the dedicated observation ingest key is not configured");
        assertThat(workflow).contains("directory_gid=\"$(stat -c %g /mormi-config)\"");
        assertThat(workflow).contains("chmod 640 \"${temporary}\"");
        assertThat(workflow).contains("MORMI_OBSERVATION_INGEST_KEY is not configured");
        assertThat(workflow).doesNotContain("echo \"${observation_ingest_key}\"");
        assertThat(workflow).doesNotContain("echo \"${OBSERVATION_INGEST_KEY}\"");
    }
}

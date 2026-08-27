package com.mormi.backend.dialogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import com.mormi.backend.curriculum.AmusementParkCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** BE → AI 놀이동산 시작 계약. BE는 시나리오 신원만 보내고 교육 콘텐츠는 보내지 않는다. */
class ParkDialogueAiContractTest {

    private static final List<String> SCENARIO_IDS = List.of(
            "amusement_ticket_multiply", "amusement_snack_divide", "amusement_pass_compare");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, Object> cafeSessionCreateBody(String scenarioId) {
        Map<String, Object> body = DialogueService.baseRequest(
                1L, true, "permanent", "cafe", scenarioId);
        body.put("learning_session_id", "cafe_visit_contract");
        body.put("conversation_round", 3);
        if ("cafe_queue".equals(scenarioId)) {
            body.put("queue_context", Map.of("left_count", 2, "right_count", 5));
        } else {
            Map<String, Object> cafeContext = new java.util.LinkedHashMap<>();
            cafeContext.put("menu_items", List.of(
                    Map.of("id", "americano", "name", "아메리카노", "price", 3000),
                    Map.of("id", "cookie", "name", "쿠키", "price", 2000)));
            cafeContext.put("mormi_menu_id", "americano");
            if ("cafe_budget_menu".equals(scenarioId)) {
                cafeContext.put("budget", 7000);
            }
            body.put("cafe_context", cafeContext);
        }
        return body;
    }

    private static Map<String, Object> sessionCreateBody(String scenarioId) {
        Map<String, Object> body = DialogueService.baseRequest(
                1L, true, "permanent", "amusement_park", scenarioId);
        body.put("learning_session_id", "park_visit_contract");
        body.put("conversation_round", 2);
        return body;
    }

    @Test
    void 놀이동산_시작_본문에는_시나리오_신원만_있다() {
        for (String scenarioId : SCENARIO_IDS) {
            Map<String, Object> body = sessionCreateBody(scenarioId);
            assertThat(body)
                    .containsEntry("scene", "amusement_park")
                    .containsEntry("scenario_id", scenarioId)
                    .containsEntry("learning_session_id", "park_visit_contract")
                    .containsEntry("conversation_round", 2)
                    .doesNotContainKeys("park_context", "facts", "prompt", "transfer");
        }
        assertThat(AmusementParkCatalog.stages())
                .extracting(AmusementParkCatalog.StageContent::scenarioId)
                .containsExactlyElementsOf(SCENARIO_IDS);
    }

    @Test
    void 세_시나리오_본문은_실제_AI_SessionCreate_스키마를_통과한다() throws Exception {
        List<Map<String, Object>> accept = SCENARIO_IDS.stream()
                .map(ParkDialogueAiContractTest::sessionCreateBody)
                .toList();
        JsonNode result = runAiSchemaCheck(Map.of("accept", accept, "reject", List.of()));
        assertThat(result.path("failures").valueStream().map(JsonNode::asString).toList())
                .as("실제 AI SessionCreate 스키마 검증")
                .isEmpty();
    }

    @Test
    void 카페_네_시나리오도_V3_identity와_화면_context로_AI_스키마를_통과한다()
            throws Exception {
        List<Map<String, Object>> accept = List.of(
                cafeSessionCreateBody("cafe_queue"),
                cafeSessionCreateBody("cafe_budget_menu"),
                cafeSessionCreateBody("cafe_menu_total"),
                cafeSessionCreateBody("cafe_change"));

        JsonNode result = runAiSchemaCheck(Map.of("accept", accept, "reject", List.of()));

        assertThat(result.path("failures").valueStream().map(JsonNode::asString).toList())
                .as("카페 V3 실제 AI SessionCreate 스키마 검증")
                .isEmpty();
    }

    private JsonNode runAiSchemaCheck(Map<String, Object> payload) throws Exception {
        Path aiHome = resolveAiHome();
        if (aiHome == null) {
            abort("Mormi-AI 체크아웃을 찾지 못했습니다. MORMI_AI_HOME 을 지정하세요.");
        }
        Path python = resolvePython(aiHome);
        if (python == null) {
            abort("Mormi-AI Python을 찾지 못했습니다: "
                    + String.join(", ", pythonCandidates(aiHome).stream().map(Path::toString).toList()));
        }

        ProcessBuilder builder = new ProcessBuilder(python.toString(), "-c", readCheckScript());
        builder.directory(aiHome.toFile());
        builder.environment().put("PYTHONPATH", aiHome.resolve("src").toString());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = builder.start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(JSON.writeValueAsBytes(payload));
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("AI 스키마 검사기가 응답하지 않습니다.");
        }
        assertThat(process.exitValue())
                .as("AI 스키마 검사기 실행 실패 (stdout: %s)", stdout)
                .isZero();
        return JSON.readTree(stdout);
    }

    private static Path resolveAiHome() {
        String configured = System.getenv("MORMI_AI_HOME");
        Path candidate = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of("").toAbsolutePath().getParent().resolve("Mormi-AI");
        return Files.isDirectory(candidate.resolve("src/mormi_api")) ? candidate : null;
    }

    private static Path resolvePython(Path aiHome) {
        for (Path candidate : pythonCandidates(aiHome)) {
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> pythonCandidates(Path aiHome) {
        List<Path> candidates = new ArrayList<>();
        String configured = System.getenv("MORMI_AI_PYTHON");
        if (configured != null && !configured.isBlank()) {
            candidates.add(Path.of(configured));
        }
        for (String relative : List.of(
                ".venv-repro/bin/python",
                ".venv-repro/bin/python3",
                ".venv/bin/python",
                ".venv/bin/python3")) {
            candidates.add(aiHome.resolve(relative));
        }
        return candidates;
    }

    private static String readCheckScript() throws IOException {
        try (InputStream in = ParkDialogueAiContractTest.class
                .getResourceAsStream("/contracts/ai_session_create_check.py")) {
            if (in == null) {
                throw new IllegalStateException("AI 스키마 검사 스크립트를 찾지 못했습니다.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

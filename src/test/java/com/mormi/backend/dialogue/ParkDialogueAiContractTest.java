package com.mormi.backend.dialogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import com.mormi.backend.curriculum.AmusementParkCatalog;
import com.mormi.backend.curriculum.AmusementParkCatalog.StageContent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * BE → AI 놀이동산 대화 시작 계약. 세 시나리오 본문이 Mormi-AI 의 <b>실제</b> SessionCreate
 * 스키마를 통과하는지 본다.
 *
 * <p>왜 필요했나: park_context.facts 에 주어진 값만 담고 아이가 구할 값을 빼면, AI 스키마는
 * facts 최소 3개·required_verified_fact_keys 포함·값끼리의 아귀(총액 = 단가 × 인원)를 모두
 * 검사하므로 대화 시작이 통째로 422 가 된다. 그런데 BE 테스트가 가짜 AI 만 상대하면 이 거절이
 * 배포 뒤에야 드러난다.
 *
 * <p>규칙을 Java 로 베껴 적으면 AI 가 계약을 바꿨을 때 같은 사각이 다시 생긴다. 그래서
 * {@code ai_session_create_check.py} 가 pydantic 모델을 그대로 import 해 검증한다. Mormi-AI
 * 체크아웃이 없는 환경(BE CI 등)에서는 이 테스트가 skip 되므로, 스키마 없이도 도는
 * 구조 검증을 {@link #세_시나리오_본문은_필수_키와_서버_계산값을_모두_담는다()} 에 함께 둔다.
 *
 * <p>Mormi-AI 위치는 {@code MORMI_AI_HOME} 환경변수, 없으면 BE 옆의 {@code ../Mormi-AI} 로 찾는다.
 */
class ParkDialogueAiContractTest {

    private static final List<String> SCENARIO_IDS = List.of(
            "amusement_ticket_multiply", "amusement_snack_divide", "amusement_pass_compare");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * BE 가 실제로 POST /v1/conversations 에 싣는 본문.
     * 겉봉과 park_context 모두 프로덕션 코드가 만든 그대로를 쓴다.
     */
    private static Map<String, Object> sessionCreateBody(
            String scenarioId, Map<String, Integer> visitFacts) {
        StageContent content = AmusementParkCatalog.stageByScenarioId(scenarioId);
        assertThat(content).as("알 수 없는 시나리오: %s", scenarioId).isNotNull();
        Map<String, Object> body = DialogueService.baseRequest(
                1L, true, "permanent", "amusement_park", scenarioId);
        body.put("park_context", DialogueService.parkContextMap(content, visitFacts));
        return body;
    }

    /** 고쳐지기 전 모양: 주어진 값만 facts 에 담고 파생값은 키 목록에만 남긴다. */
    private static Map<String, Object> bodyWithGivenFactsOnly(
            String scenarioId, Map<String, Integer> visitFacts) {
        Map<String, Object> body = sessionCreateBody(scenarioId, visitFacts);
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) body.get("park_context");
        List<String> givenKeys = AmusementParkCatalog.stageByScenarioId(scenarioId).factKeys();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = (List<Map<String, Object>>) context.get("facts");
        context.put("facts", facts.stream().filter(fact -> givenKeys.contains(fact.get("key"))).toList());
        return body;
    }

    @Test
    void 세_시나리오_본문은_필수_키와_서버_계산값을_모두_담는다() {
        Random random = new Random(20260824L);  // 시드를 고정해 실패를 재현할 수 있게 한다

        for (int draw = 0; draw < 200; draw++) {
            Map<String, Integer> visitFacts = AmusementParkCatalog.initialFacts(random);

            for (String scenarioId : SCENARIO_IDS) {
                StageContent content = AmusementParkCatalog.stageByScenarioId(scenarioId);
                @SuppressWarnings("unchecked")
                Map<String, Object> context =
                        (Map<String, Object>) sessionCreateBody(scenarioId, visitFacts).get("park_context");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> facts = (List<Map<String, Object>>) context.get("facts");

                // 스키마가 요구하는 키가 하나도 빠지지 않는다(예전에는 파생값이 통째로 빠져 있었다).
                assertThat(facts.stream().map(fact -> fact.get("key")))
                        .containsExactlyElementsOf(content.requiredVerifiedFactKeys());
                assertThat(context.get("required_verified_fact_keys"))
                        .isEqualTo(content.requiredVerifiedFactKeys());
                assertThat(facts).hasSizeBetween(3, 12);

                // 값은 서버 계산 결과이고, label·unit 은 카탈로그가 소유한다.
                Map<String, Integer> verified =
                        AmusementParkCatalog.verifiedFacts(content.stageId(), visitFacts);
                for (Map<String, Object> fact : facts) {
                    String key = (String) fact.get("key");
                    assertThat(fact.get("value")).as("%s 값", key).isEqualTo(verified.get(key));
                    assertThat((String) fact.get("label")).as("%s label", key).isNotBlank();
                    assertThat((String) fact.get("unit")).as("%s unit", key).isNotBlank();
                }
            }
        }
    }

    @Test
    void 세_시나리오_본문은_실제_AI_SessionCreate_스키마를_통과한다() throws Exception {
        Random random = new Random(20260824L);
        List<Map<String, Object>> accept = new ArrayList<>();
        List<Map<String, Object>> reject = new ArrayList<>();

        // 숫자는 방문마다 뽑히므로 한 판이 아니라 여러 출제를 통째로 스키마에 넣어 본다.
        for (int draw = 0; draw < 20; draw++) {
            Map<String, Integer> visitFacts = AmusementParkCatalog.initialFacts(random);
            for (String scenarioId : SCENARIO_IDS) {
                accept.add(sessionCreateBody(scenarioId, visitFacts));
                reject.add(bodyWithGivenFactsOnly(scenarioId, visitFacts));
            }
        }

        JsonNode result = runAiSchemaCheck(Map.of("accept", accept, "reject", reject));

        assertThat(result.path("failures").valueStream().map(JsonNode::asString).toList())
                .as("실제 AI SessionCreate 스키마 검증")
                .isEmpty();
    }

    /** Mormi-AI 를 그대로 import 하는 파이썬 검사기를 돌린다. 없으면 이유를 남기고 skip 한다. */
    private JsonNode runAiSchemaCheck(Map<String, Object> payload) throws Exception {
        Path aiHome = resolveAiHome();
        if (aiHome == null) {
            abort("Mormi-AI 체크아웃을 찾지 못해 실제 스키마 검증을 건너뜁니다. "
                    + "MORMI_AI_HOME 을 지정하거나 BE 옆에 Mormi-AI 를 두세요.");
        }
        Path python = resolvePython(aiHome);
        if (python == null) {
            abort("Mormi-AI 의 파이썬 실행 파일을 찾지 못해 실제 스키마 검증을 건너뜁니다: "
                    + aiHome.resolve(".venv/bin/python"));
        }

        ProcessBuilder builder = new ProcessBuilder(
                python.toString(), "-c", readCheckScript());
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

    /** 스키마는 pydantic 위에 있으므로 시스템 파이썬이 아니라 Mormi-AI 의 venv 를 먼저 본다. */
    private static Path resolvePython(Path aiHome) {
        for (String relative : List.of(".venv/bin/python", ".venv/bin/python3")) {
            Path candidate = aiHome.resolve(relative);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
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

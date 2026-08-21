package com.mormi.backend.starnote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.dialogue.DialogueConversation;
import com.mormi.backend.dialogue.DialogueConversationRepository;
import com.mormi.backend.observation.ObservationIngestService;
import com.mormi.backend.session.LearningSessionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.mormi.backend.AuthTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 별노트 목록 API 가 지켜야 하는 것들을 실제 PostgreSQL 로 검증한다.
 * 1) 본인 것만, 활성 노트만, 최신순(동률은 note_id 내림차순)으로 보인다
 * 2) 커서를 따라가면 중복·누락 없이 전부 읽힌다
 * 3) 남의 목록은 403, 토큰 없이는 401, 모르는 커서는 422
 */
@SpringBootTest(properties = "mormi.observation.ingest-key=test-ingest-key")
@AutoConfigureMockMvc
@Testcontainers
class StarNoteListIntegrationTest {

    private static final String INGEST_PATH = "/internal/v1/observations/events";
    private static final String SERVICE_KEY_HEADER = "X-Mormi-Service-Key";
    private static final String SERVICE_KEY = "test-ingest-key";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    DialogueConversationRepository dialogueConversationRepository;

    @Autowired
    LearningSessionRepository learningSessionRepository;

    @Test
    void 자기_별노트만_최신순으로_본다() throws Exception {
        학습자 아이 = 학습자를_만든다("MORMI-SNL-01", "conv-snl-sort");
        학습자 남 = 학습자를_만든다("MORMI-SNL-02", "conv-snl-sort-other");

        // 시각 동률(note-tie-a, note-tie-b)은 note_id 내림차순으로 갈린다.
        별노트를_넣는다(아이, "note-old", "2026-08-17T09:00:00+00:00", true);
        별노트를_넣는다(아이, "note-tie-a", "2026-08-18T09:00:00+00:00", true);
        별노트를_넣는다(아이, "note-tie-b", "2026-08-18T09:00:00+00:00", true);
        별노트를_넣는다(아이, "note-hidden", "2026-08-19T09:00:00+00:00", false);
        별노트를_넣는다(남, "note-someone-elses", "2026-08-19T09:00:00+00:00", true);

        mockMvc.perform(get("/v1/learners/{id}/star-notes", 아이.learnerId())
                        .header("Authorization", "Bearer " + 아이.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.star_notes.length()").value(3))
                .andExpect(jsonPath("$.star_notes[0].note_id").value("note-tie-b"))
                .andExpect(jsonPath("$.star_notes[1].note_id").value("note-tie-a"))
                .andExpect(jsonPath("$.star_notes[2].note_id").value("note-old"))
                // AI 원문 필드가 그대로 나간다.
                .andExpect(jsonPath("$.star_notes[0].text").value("색칠된 칸을 하나씩 세면 모두 3개야."))
                .andExpect(jsonPath("$.star_notes[0].attribution").value("child"))
                .andExpect(jsonPath("$.star_notes[0].attribution_label").value("아이가 알려줌"))
                .andExpect(jsonPath("$.star_notes[0].skill_id").value("number-count"))
                // 한 페이지에 다 들어가므로 다음 커서가 없어야 한다.
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void 커서로_다음_페이지를_잇는다() throws Exception {
        학습자 아이 = 학습자를_만든다("MORMI-SNL-03", "conv-snl-cursor");
        for (int i = 1; i <= 5; i++) {
            별노트를_넣는다(아이, "note-page-" + i, "2026-08-1" + i + "T09:00:00+00:00", true);
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var request = get("/v1/learners/{id}/star-notes", 아이.learnerId())
                    .queryParam("limit", "2")
                    .header("Authorization", "Bearer " + 아이.token());
            if (cursor != null) {
                request = request.queryParam("cursor", cursor);
            }
            JsonNode body = objectMapper.readTree(mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            body.get("star_notes").forEach(note -> seen.add(note.get("note_id").asString()));
            cursor = body.has("next_cursor") ? body.get("next_cursor").asString() : null;
            pages++;
        } while (cursor != null);

        // 2+2+1 세 페이지. 중복도 누락도 없이 최신순 그대로 이어져야 한다.
        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactly(
                "note-page-5", "note-page-4", "note-page-3", "note-page-2", "note-page-1");
    }

    @Test
    void 다른_학습자의_별노트는_403() throws Exception {
        학습자 주인 = 학습자를_만든다("MORMI-SNL-04", "conv-snl-owner");
        학습자 남 = 학습자를_만든다("MORMI-SNL-05", "conv-snl-intruder");
        별노트를_넣는다(주인, "note-private", "2026-08-19T09:00:00+00:00", true);

        mockMvc.perform(get("/v1/learners/{id}/star-notes", 주인.learnerId())
                        .header("Authorization", "Bearer " + 남.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void 토큰_없이는_401() throws Exception {
        mockMvc.perform(get("/v1/learners/{id}/star-notes", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void 모르는_커서는_422로_알린다() throws Exception {
        학습자 아이 = 학습자를_만든다("MORMI-SNL-06", "conv-snl-badcursor");

        mockMvc.perform(get("/v1/learners/{id}/star-notes", 아이.learnerId())
                        .queryParam("cursor", "note-never-existed")
                        .header("Authorization", "Bearer " + 아이.token()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("invalid_cursor"));
    }

    /** 별노트는 수집 API 를 통해 넣는다. 원장을 직접 조작하지 않고 실제 유입 경로를 그대로 탄다. */
    private void 별노트를_넣는다(학습자 학습자, String noteId, String createdAt, boolean active)
            throws Exception {
        Map<String, Object> starNote = new LinkedHashMap<>();
        starNote.put("note_id", noteId);
        starNote.put("note_version", 1);
        starNote.put("conversation_id", 학습자.conversationId());
        starNote.put("skill_id", "number-count");
        starNote.put("text", "색칠된 칸을 하나씩 세면 모두 3개야.");
        starNote.put("attribution", "child");
        starNote.put("attribution_label", "아이가 알려줌");
        starNote.put("evidence", "direct_explanation");
        starNote.put("active", active);
        starNote.put("created_at", createdAt);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", "evt-" + noteId);
        event.put("schema_version", ObservationIngestService.SCHEMA_VERSION);
        event.put("event_type", "star_note_created");
        event.put("star_note", starNote);

        mockMvc.perform(post(INGEST_PATH)
                        .header(SERVICE_KEY_HEADER, SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk());
    }

    private record 학습자(Long learnerId, String token, String conversationId) {
    }

    /** 학습자·세션은 API 로 만들고, 대화 소유권 행만 직접 넣는다. */
    private 학습자 학습자를_만든다(String researchCode, String conversationId) throws Exception {
        JsonNode learner = AuthTestSupport.signupLearner(mockMvc, objectMapper, "별노트", researchCode);
        String token = learner.get("access_token").asString();

        String sessionBody = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("curriculum_session_id", "money-count", "variant_seed", 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String publicId = objectMapper.readTree(sessionBody).get("learning_session_id").asString();
        Long sessionId = learningSessionRepository.findByPublicId(publicId).orElseThrow().getId();

        Long learnerId = learner.get("id").asLong();
        dialogueConversationRepository.save(DialogueConversation.forLearningSession(
                conversationId, learnerId, sessionId));
        return new 학습자(learnerId, token, conversationId);
    }
}

package com.mormi.backend.dialogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mormi.backend.AuthTestSupport;
import com.mormi.backend.session.LearningSessionRepository;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 자유 발화 한 턴은 AI 응답을 약 10초 기다린다. 그 대기를 트랜잭션 안에서 하면
 * DB 커넥션 하나가 그 시간 내내 묶여, 기본 풀(10)에서 동시 20명이 천장이 된다
 * (부하테스트 2026-08-27: 30명에서 커넥션 대기 p50 +8.6s, 30초 타임아웃 503).
 *
 * <p>이 테스트는 그 회귀를 막는다. {@code DialogueService.respond()} 나
 * {@code getConversation()} 에 {@code @Transactional} 이 다시 붙으면
 * AI 호출 시점에 트랜잭션이 살아 있게 되고 아래 단언이 깨진다.
 *
 * <p>단위 테스트로는 잡히지 않는다. 프록시가 없는 곳에서는 {@code @Transactional} 이
 * 아무 일도 하지 않아 어느 쪽이든 통과하기 때문에, 실제 컨텍스트로 띄워서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DialogueTransactionBoundaryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 실제 AI 대신, 호출된 순간의 트랜잭션 상태를 기록하는 대역. */
    @MockitoBean
    DialogueClient dialogueClient;

    @Autowired
    DialogueService dialogueService;

    @Autowired
    DialogueConversationRepository dialogueConversationRepository;

    @Autowired
    LearningSessionRepository learningSessionRepository;

    @Autowired
    MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 자유발화_AI_호출은_트랜잭션_밖에서_기다린다() throws Exception {
        대화 대화 = 대화를_만든다("MORMI-TX-01", "conv-tx-respond");
        JsonNode 아이_응답 = objectMapper.readTree("{\"turn_id\":\"turn-1\"}");
        AtomicBoolean 트랜잭션_열려있었나 = new AtomicBoolean(true);

        when(dialogueClient.respond(eq(대화.conversationId()), any())).thenAnswer(호출 -> {
            트랜잭션_열려있었나.set(TransactionSynchronizationManager.isActualTransactionActive());
            return 봉투(대화.conversationId());
        });

        dialogueService.respond(대화.learnerId(), 대화.conversationId(), 아이_응답);

        assertThat(트랜잭션_열려있었나)
                .withFailMessage("respond() 가 AI 응답을 트랜잭션 안에서 기다린다. "
                        + "@Transactional 이 다시 붙었는지 확인할 것.")
                .isFalse();
    }

    @Test
    void 대화_조회_AI_호출도_트랜잭션_밖에서_기다린다() throws Exception {
        대화 대화 = 대화를_만든다("MORMI-TX-02", "conv-tx-get");
        AtomicBoolean 트랜잭션_열려있었나 = new AtomicBoolean(true);

        when(dialogueClient.getConversation(대화.conversationId())).thenAnswer(호출 -> {
            트랜잭션_열려있었나.set(TransactionSynchronizationManager.isActualTransactionActive());
            return 봉투(대화.conversationId());
        });

        dialogueService.getConversation(대화.learnerId(), 대화.conversationId());

        assertThat(트랜잭션_열려있었나)
                .withFailMessage("getConversation() 이 AI 응답을 트랜잭션 안에서 기다린다.")
                .isFalse();
    }

    private JsonNode 봉투(String conversationId) {
        return objectMapper.readTree("""
                {
                  "conversation_id": "%s",
                  "turn": {"status": "in_progress", "state_version": 1}
                }
                """.formatted(conversationId));
    }

    private record 대화(String conversationId, Long learnerId) {
    }

    /**
     * 집 가르치기 대화로 세운다. 장소 대화가 아니어서 단계 반영이 없고,
     * 이 테스트가 보려는 것(AI 호출 시점의 트랜잭션 상태)만 남는다.
     */
    private 대화 대화를_만든다(String researchCode, String conversationId) throws Exception {
        JsonNode learner = AuthTestSupport.signupLearner(mockMvc, objectMapper, "경계", researchCode);

        String sessionBody = mockMvc.perform(post("/v1/learning-sessions")
                        .header("Authorization", "Bearer " + learner.get("access_token").asString())
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
        return new 대화(conversationId, learnerId);
    }
}

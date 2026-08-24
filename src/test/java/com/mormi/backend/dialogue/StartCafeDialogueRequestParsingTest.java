package com.mormi.backend.dialogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.mormi.backend.dialogue.DialogueDtos.StartCafeDialogueRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;

/**
 * 배포 FE(f8d1abb 이후)는 카페 대화 시작 본문에 restart 를 더 이상 싣지 않고
 * start_mode 만 보낸다. 이 본문이 파싱 단계에서 거절되면 안 된다.
 */
@JsonTest
class StartCafeDialogueRequestParsingTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void start_mode만_보내는_새_FE_본문도_파싱된다() {
        String body = """
                {"scenario_id":"cafe_queue",
                 "queue_context":{"left_count":4,"right_count":1},
                 "start_mode":"restart",
                 "request_id":"req-1"}""";
        StartCafeDialogueRequest request = mapper.readValue(body, StartCafeDialogueRequest.class);
        assertThat(request.wantsRestart()).isTrue();
    }

    @Test
    void restart를_같이_보내는_옛_FE_본문도_그대로_파싱된다() {
        String body = """
                {"scenario_id":"cafe_queue",
                 "queue_context":{"left_count":4,"right_count":1},
                 "start_mode":"restart",
                 "request_id":"req-1",
                 "restart":false}""";
        StartCafeDialogueRequest request = mapper.readValue(body, StartCafeDialogueRequest.class);
        assertThat(request.wantsRestart()).isTrue();
    }
}

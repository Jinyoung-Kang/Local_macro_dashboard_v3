package com.macrodash.feature.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 호출 실패 로그에 요청 주소(Cloudflare는 경로에 계정 ID)가 남지 않는다. */
class AiServiceLogRedactionTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(AiService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("QA-025 연결 실패 로그에 요청 주소(계정 ID가 든 경로)를 남기지 않는다")
    void failureLogDoesNotCarryRequestUrl() {
        appender.start();
        logger.addAppender(appender);
        AiService service = new AiService(5, 5, 5);

        // 포트 1은 연결이 바로 거부됩니다. 경로 모양은 Cloudflare Workers AI와 같습니다.
        Map<String, Object> result = service.callOpenAiFormat("Cloudflare", 
                "http://127.0.0.1:1/client/v4/accounts/QA025ACCOUNTID/ai/run/m", "token", "m", "질문", null);

        assertThat(result).containsEntry("status", false);
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain("QA025ACCOUNTID"));
    }
}

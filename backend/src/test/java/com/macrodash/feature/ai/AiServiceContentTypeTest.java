package com.macrodash.feature.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 응답을 Content-Type에 의존하지 않고 읽는지 확인합니다.
 *
 * <p>실제로 화면에 떴던 오류입니다.
 * <pre>
 * 생성 실패: Error while extracting response for type
 *   [tools.jackson.databind.JsonNode]
 *   and content type [application/octet-stream]
 * </pre>
 *
 * <p>NVIDIA는 같은 엔드포인트인데도 모델에 따라 {@code application/octet-stream}을
 * 붙여 보내는 경우가 있습니다. 본문은 멀쩡한 JSON인데 Jackson 컨버터가
 * {@code application/json} 계열만 처리해서 거절하던 것이었습니다.
 */
class AiServiceContentTypeTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(String contentType, String body) throws Exception {
        return startServer(contentType, body, 200);
    }

    private String startServer(String contentType, String body, int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
    }

    private static final String OPENAI_BODY = """
            {"choices":[{"message":{"content":"분석 결과입니다."}}]}
            """;

    @Test
    @DisplayName("application/octet-stream으로 와도 JSON으로 읽는다")
    void readsJsonDespiteOctetStreamContentType() throws Exception {
        String endpoint = startServer("application/octet-stream", OPENAI_BODY);

        Map<String, Object> result = new AiService()
                .callOpenAiFormat("테스트", endpoint, "dummy-key", "test-model", "질문", null);

        assertThat(result.get("status")).as("octet-stream이어도 성공해야 합니다").isEqualTo(true);
        assertThat(result.get("response")).isEqualTo("분석 결과입니다.");
    }

    @Test
    @DisplayName("정상 application/json도 그대로 동작한다")
    void stillReadsPlainJson() throws Exception {
        String endpoint = startServer("application/json", OPENAI_BODY);

        Map<String, Object> result = new AiService()
                .callOpenAiFormat("테스트", endpoint, "dummy-key", "test-model", "질문", null);

        assertThat(result.get("status")).isEqualTo(true);
        assertThat(result.get("response")).isEqualTo("분석 결과입니다.");
    }

    @Test
    @DisplayName("본문이 JSON이 아니면 실패로 보고하고, 서버가 보낸 본문을 같이 보여준다")
    void reportsFailureOnNonJsonBodyWithPreview() throws Exception {
        // 오류 메시지가 "읽지 못했습니다"에서 끝나면 다음에 또 추측해야 합니다.
        String endpoint = startServer("text/html", "<html>프록시가 막았습니다</html>");

        Map<String, Object> result = new AiService()
                .callOpenAiFormat("테스트", endpoint, "dummy-key", "test-model", "질문", null);

        assertThat(result.get("status")).isEqualTo(false);
        assertThat(String.valueOf(result.get("error")))
                .as("서버가 실제로 보낸 내용이 메시지에 있어야 합니다")
                .contains("프록시가 막았습니다");
    }

    @Test
    @DisplayName("4xx면 상태코드와 서버 설명을 함께 남긴다")
    void reportsHttpErrorWithServerMessage() throws Exception {
        String endpoint = startServer("application/json",
                "{\"error\":{\"message\":\"model not found\"}}", 404);

        Map<String, Object> result = new AiService()
                .callOpenAiFormat("테스트", endpoint, "dummy-key", "test-model", "질문", null);

        assertThat(result.get("status")).isEqualTo(false);
        String error = String.valueOf(result.get("error"));
        assertThat(error).contains("404");
        assertThat(error).contains("model not found");
    }

    @Test
    @DisplayName("빈 본문도 실패로 보고한다")
    void reportsFailureOnEmptyBody() throws Exception {
        String endpoint = startServer("application/octet-stream", "");

        Map<String, Object> result = new AiService()
                .callOpenAiFormat("테스트", endpoint, "dummy-key", "test-model", "질문", null);

        assertThat(result.get("status")).isEqualTo(false);
    }

    @Test
    @DisplayName("키가 없으면 호출하지 않고 이유를 남긴다")
    void missingKeyIsReportedNotSilentlySkipped() {
        AtomicReference<Map<String, Object>> result = new AtomicReference<>(
                new AiService().callOpenAiFormat(
                        "테스트", "http://127.0.0.1:1/chat", "", "test-model", "질문", null));

        assertThat(result.get().get("status")).isEqualTo(false);
        assertThat(String.valueOf(result.get().get("error"))).contains("API Key 누락");
    }

    // =========================================================================
    // 오류 문구는 "다음에 뭘 하면 되는지"까지 말해야 합니다.
    // =========================================================================
    // 화면에 이렇게만 떴습니다.
    //   생성 실패: I/O error on POST request for "...": Read timed out
    // 읽는 사람이 할 수 있는 일이 없습니다.

    @Test
    @DisplayName("타임아웃이면 대기 시간과 대안을 알려준다")
    void timeoutMessageIsActionable() {
        String message = new AiService()
                .describe(new RuntimeException("I/O error on POST request: Read timed out"));

        assertThat(message).contains("240초");
        assertThat(message).contains("자동 탐색");
        assertThat(message).contains("AI_TIMEOUT_SECONDS");
        assertThat(message).doesNotContain("Read timed out");
    }

    @Test
    @DisplayName("401·404·429는 원인별로 다르게 말한다")
    void httpErrorsAreExplained() {
        AiService service = new AiService();

        assertThat(service.describe(new RuntimeException("401 Unauthorized")))
                .contains("API 키");
        assertThat(service.describe(new RuntimeException("404 Not Found: no such model")))
                .contains("모델");
        assertThat(service.describe(new RuntimeException("429 Too Many Requests")))
                .contains("한도");
    }

    @Test
    @DisplayName("모르는 오류는 그대로 보여준다 — 설명을 지어내지 않는다")
    void unknownErrorIsPassedThrough() {
        String raw = "connection reset by peer";
        assertThat(new AiService().describe(new RuntimeException(raw))).isEqualTo(raw);
    }

    @Test
    @DisplayName("대기 한도는 설정으로 바꿀 수 있고 최소값이 있다")
    void timeoutIsConfigurableWithFloor() {
        assertThat(new AiService(600).describe(new RuntimeException("Read timed out")))
                .contains("600초");
        // 1초 같은 값을 넣어 모든 호출을 실패시키는 일은 막습니다.
        assertThat(new AiService(1).describe(new RuntimeException("Read timed out")))
                .contains("30초");
    }

}

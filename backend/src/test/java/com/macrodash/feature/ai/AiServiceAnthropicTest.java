package com.macrodash.feature.ai;

import com.anthropic.client.AnthropicClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claude Haiku 5.5 엔진 — 공식 SDK를 실제로 돌리고, Claude API 자리에 가짜 서버를 둡니다(유료 호출 없음).
 *
 * <p>요청 모양(모델·한도·effort·temperature 없음·키 헤더), 사고 블록이 앞에 오는 응답, 안전 분류기 거절,
 * 인증 실패, 호출 한도(재시도 없이 한 번만), 키 없음을 고정합니다.
 */
class AiServiceAnthropicTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODEL = "claude-haiku-5-5";

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> apiKeys = new CopyOnWriteArrayList<>();
    private final List<String> timeoutHeaders = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** /v1/messages에 status·body로 답하는 가짜 Claude API. 받은 요청 본문과 키 헤더를 기록합니다. */
    private String startServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            hits.incrementAndGet();
            requests.add(MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
            apiKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")));
            // SDK가 그 호출에 실제로 건 전체 대기 한도(초)를 이 헤더로 보냅니다.
            timeoutHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Stainless-Timeout")));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static AiService service(String baseUrl, String key) {
        AiService service = new AiService(240, 60, 180);
        for (String field : List.of("nvidiaKey", "cerebrasKey", "cloudflareAccountId", "cloudflareToken")) {
            ReflectionTestUtils.setField(service, field, "");
        }
        ReflectionTestUtils.setField(service, "anthropicKey", key);
        service.anthropicBaseUrl = baseUrl;
        return service;
    }

    private static String message(String content, String stopReason, String extra) {
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5",
                 "content":%s,"stop_reason":"%s","stop_sequence":null%s,
                 "usage":{"input_tokens":1200,"output_tokens":340}}
                """.formatted(content, stopReason, extra);
    }

    @Test
    @DisplayName("사고 블록이 앞에 와도 텍스트 블록만 답으로 쓰고, Haiku 5.5에 맞는 요청을 보낸다")
    void readsTextAfterThinkingAndSendsHaikuShapedRequest() throws Exception {
        String base = startServer(200, message("""
                [{"type":"thinking","thinking":"","signature":"sig"},
                 {"type":"text","text":"## 요약\\n"},{"type":"text","text":"분석 결과입니다."}]
                """, "end_turn", ""));

        Map<String, Object> result = service(base, "test-key").generate("anthropic_haiku", "질문", "한국어로 답하십시오.");

        assertThat(result.get("status")).isEqualTo(true);
        assertThat(result.get("response")).as("텍스트 블록만, 순서대로").isEqualTo("## 요약\n분석 결과입니다.");
        assertThat(result.get("model")).isEqualTo(MODEL);
        assertThat(result.get("usage")).isEqualTo(Map.of("inputTokens", 1200L, "outputTokens", 340L));
        assertThat(result.get("translationInfo")).as("한국어 응답이면 번역하지 않음").isEqualTo("번역 불필요 — 한국어 응답");

        JsonNode sent = requests.get(0);
        assertThat(sent.path("model").asText()).isEqualTo(MODEL);
        assertThat(sent.path("max_tokens").asLong()).isEqualTo(AiService.ANTHROPIC_MAX_TOKENS);
        assertThat(sent.path("output_config").path("effort").asText()).isEqualTo("medium");
        assertThat(sent.has("temperature")).as("Haiku 5.5는 기본값이 아닌 temperature에 400").isFalse();
        assertThat(sent.has("thinking")).as("사고는 기본(adaptive)으로 둔다").isFalse();
        assertThat(sent.path("system").asText()).isEqualTo("한국어로 답하십시오.");
        assertThat(apiKeys).containsExactly("test-key");
    }

    @Test
    @DisplayName("안전 분류기가 거절하면 실패로 보고하고 분류·설명을 남긴다")
    void reportsRefusalWithCategory() throws Exception {
        String base = startServer(200, message("[]", "refusal",
                ",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"정책상 답할 수 없습니다\"}"));

        Map<String, Object> result = service(base, "test-key").generate("anthropic_haiku", "질문", null);

        assertThat(result.get("status")).isEqualTo(false);
        assertThat(String.valueOf(result.get("error"))).contains("거절", "cyber", "정책상 답할 수 없습니다");
    }

    @Test
    @DisplayName("QA-021 모델이 응답한 뒤의 실패(거절)는 과금된 것으로, 거절된 요청(429)·키 없음은 과금되지 않은 것으로 표시한다")
    void marksWhetherTheFailedCallWasCharged() throws Exception {
        String refused = startServer(200, message("[]", "refusal", ""));
        assertThat(service(refused, "test-key").generate("anthropic_haiku", "질문", null))
                .containsEntry("status", false).containsEntry("charged", true);
        stop();

        String limited = startServer(429,
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
        assertThat(service(limited, "test-key").generate("anthropic_haiku", "질문", null))
                .containsEntry("status", false).containsEntry("charged", false);

        assertThat(service(limited, "").callAnthropic("Claude", MODEL, "질문", null, 30))
                .containsEntry("status", false).containsEntry("charged", false);
    }

    @Test
    @DisplayName("사고만 하다 출력 한도에 걸려 텍스트가 없으면 그 이유를 말한다")
    void explainsMaxTokensWithoutText() throws Exception {
        String base = startServer(200, message("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"}]",
                "max_tokens", ""));

        Map<String, Object> result = service(base, "test-key").generate("anthropic_haiku", "질문", null);

        assertThat(result.get("status")).isEqualTo(false);
        assertThat(String.valueOf(result.get("error"))).contains("출력 한도");
    }

    @Test
    @DisplayName("401이면 어떤 환경 변수를 확인할지 알려 준다")
    void unauthorizedPointsToEnvVariable() throws Exception {
        String base = startServer(401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");

        Map<String, Object> result = service(base, "wrong-key").generate("anthropic_haiku", "질문", null);

        assertThat(result.get("status")).isEqualTo(false);
        assertThat(String.valueOf(result.get("error"))).contains("401", "ANTHROPIC_API_KEY");
        assertThat(String.valueOf(result.get("error"))).doesNotContain("wrong-key");
    }

    @Test
    @DisplayName("429는 한 번만 보낸다 — 재시도는 SDK가 아니라 자동 탐색이 맡는다(엔진당 대기 한도 유지)")
    void rateLimitIsNotRetriedBySdk() throws Exception {
        String base = startServer(429,
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");

        Map<String, Object> result = service(base, "test-key").generate("anthropic_haiku", "질문", null);

        assertThat(result.get("status")).isEqualTo(false);
        assertThat(String.valueOf(result.get("error"))).contains("429");
        assertThat(hits.get()).as("SDK 자동 재시도가 꺼져 있어야 합니다").isEqualTo(1);
    }

    @Test
    @Disabled("QA-119: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-119 대기 한도가 호출마다 달라도(번역의 남은 초 15~110) Claude 클라이언트는 하나만 만들고, 한도는 그 호출에 건다")
    void oneClientServesEveryAttemptBudget() throws Exception {
        String base = startServer(200, message("[{\"type\":\"text\",\"text\":\"답입니다.\"}]", "end_turn", ""));
        AtomicInteger created = new AtomicInteger();
        AiService service = new AiService(110, 60, 180) {
            @Override
            AnthropicClient newAnthropicClient(int seconds) {
                created.incrementAndGet();
                return super.newAnthropicClient(seconds);
            }
        };
        for (String field : List.of("nvidiaKey", "cerebrasKey", "cloudflareAccountId", "cloudflareToken")) {
            ReflectionTestUtils.setField(service, field, "");
        }
        ReflectionTestUtils.setField(service, "anthropicKey", "test-key");
        service.anthropicBaseUrl = base;

        // AWS(대기 한도 110초, 늘 직접 경로)에서 번역이 받을 수 있는 남은 초 전부.
        for (int seconds = 15; seconds <= 110; seconds++) {
            assertThat(service.callAnthropic("Claude", MODEL, "질문", null, seconds)).containsEntry("status", true);
        }

        assertThat(created.get()).as("클라이언트마다 SDK 타이머 스레드·연결 풀이 생겨 재시작 전까지 남음").isEqualTo(1);
        assertThat(timeoutHeaders).as("호출마다 그 시도의 대기 한도")
                .containsExactlyElementsOf(IntStream.rangeClosed(15, 110).mapToObj(String::valueOf).toList());
    }

    @Test
    @DisplayName("키가 없으면 네트워크에 나가지 않고, 자동 탐색은 Claude를 '키 없음'으로 건너뛴다")
    void missingKeyIsSkipped() throws Exception {
        String base = startServer(200, message("[{\"type\":\"text\",\"text\":\"호출되면 안 됩니다\"}]", "end_turn", ""));
        AiService service = service(base, "");

        Map<String, Object> direct = service.callAnthropic("Claude", MODEL, "질문", null, 30);
        assertThat(direct.get("status")).isEqualTo(false);
        assertThat(String.valueOf(direct.get("error"))).contains("ANTHROPIC_API_KEY");
        assertThat(hits.get()).isZero();

        Map<String, Object> engines = service.engines();
        assertThat(engines.get("enabled")).as("어떤 키도 없으면 AI 메뉴는 꺼짐").isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> keys = (Map<String, Boolean>) engines.get("keys");
        assertThat(keys).containsEntry("anthropic", false);
    }

    @Test
    @DisplayName("Claude 키만 있어도 AI 메뉴가 켜지고, 엔진 목록에 Claude Haiku 5.5가 사용 가능으로 나온다")
    void anthropicKeyAloneEnablesAi() {
        AiService service = service(null, "test-key");

        Map<String, Object> engines = service.engines();

        assertThat(engines.get("enabled")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) engines.get("engines");
        assertThat(list).anySatisfy(engine -> {
            assertThat(engine.get("id")).isEqualTo("anthropic_haiku");
            assertThat(engine.get("model")).isEqualTo(MODEL);
            assertThat(engine.get("available")).isEqualTo(true);
        });
    }
}

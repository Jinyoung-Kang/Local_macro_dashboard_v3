package com.macrodash.collector;

import com.macrodash.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 수집기(FastAPI) 호출 클라이언트.
 *
 * <p>백엔드는 스스로 외부 시장 데이터를 긁지 않습니다. 키 관리와 파싱을 한 곳에
 * 모아 두면 "진단은 통과하는데 화면은 비는" 어긋남이 생기지 않습니다.
 *
 * <p>수집기가 죽어 있어도 백엔드는 살아 있어야 합니다. 모든 호출은 실패를
 * {@link Optional#empty()}로 돌려주고, 호출부는 저장본으로 화면을 그립니다.
 */
@Component
public class CollectorClient {

    private static final Logger log = LoggerFactory.getLogger(CollectorClient.class);

    private final RestClient client;
    private final AppProperties properties;

    public CollectorClient(AppProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(properties.getCollectorTimeoutSeconds()));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getCollectorUrl())
                .requestFactory(factory);

        if (!properties.getCollectorToken().isBlank()) {
            builder = builder.defaultHeader("X-Service-Token", properties.getCollectorToken());
        }
        this.client = builder.build();
    }

    /**
     * 태스크 1건 실행.
     *
     * @param wait false면 수집기가 백그라운드로 돌리고 즉시 응답합니다.
     *             보여 줄 저장본이 이미 있다면 기다릴 이유가 없습니다 —
     *             기다리면 그만큼 화면이 멈춥니다(sec_13f 31.8초, fred_series 11.5초).
     */
    public Optional<JsonNode> runTask(String taskName, boolean wait) {
        return post(uri -> uri.path("/collect/task/{task}")
                .queryParam("wait", wait)
                .build(taskName), Map.of(), "/collect/task/" + taskName);
    }

    /** 작업군 전체 실행 (수동 새로고침 버튼 등). */
    public Optional<JsonNode> runGroup(String group, boolean wait) {
        return post(uri -> uri.path("/collect")
                .queryParam("group", group)
                .queryParam("wait", wait)
                .build(), Map.of(), "/collect");
    }

    public Optional<JsonNode> requestRefresh() {
        return post("/refresh", Map.of());
    }

    public Optional<JsonNode> status() {
        return get("/status");
    }

    public Optional<JsonNode> tasks() {
        return get("/tasks");
    }

    public Optional<JsonNode> taskHistory(String task, int limit) {
        return get(uri -> uri.path("/task-history")
                .queryParam("limit", limit)
                .queryParamIfPresent("task", Optional.ofNullable(task))
                .build(), "/task-history");
    }

    public Optional<JsonNode> diagnostics() {
        return get("/diagnostics/connections");
    }

    /** 국내 공공 API 연결 진단 (API마다 1회 호출). 결과에 키는 없습니다. */
    public Optional<JsonNode> publicApiDiagnostics() {
        return get("/diagnostics/public-apis");
    }

    public Optional<JsonNode> tossDiagnostics() {
        return get("/diagnostics/toss");
    }

    public Optional<JsonNode> tossExchangeRate(String base, String quote) {
        return get(uri -> uri.path("/toss/exchange-rate")
                .queryParam("base", base)
                .queryParam("quote", quote)
                .build(), "/toss/exchange-rate");
    }

    public Optional<JsonNode> tossIndices(String symbols) {
        return get(uri -> uri.path("/toss/indices")
                .queryParam("symbols", symbols)
                .build(), "/toss/indices");
    }

    public Optional<JsonNode> verificationReadings(String market, String investor, String tradeType) {
        return get(uri -> uri.path("/verify/readings")
                .queryParam("market", market)
                .queryParam("investor", investor)
                .queryParam("tradeType", tradeType)
                .build(), "/verify/readings");
    }

    public Optional<JsonNode> liveRadar(String market, String investor, String tradeType,
                                        int topN, String intervalType, String targetDate) {
        return get(uri -> uri.path("/live/radar")
                .queryParam("market", market)
                .queryParam("investor", investor)
                .queryParam("tradeType", tradeType)
                .queryParam("topN", topN)
                .queryParam("intervalType", intervalType)
                .queryParamIfPresent("targetDate", Optional.ofNullable(targetDate))
                .build(), "/live/radar");
    }

    public Optional<JsonNode> liveTicker(String symbol, String period) {
        // 티커는 경로에 들어가고 '^VIX'처럼 그냥 쓸 수 없는 글자가 섞입니다.
        // 문자열로 이어 붙이지 않고 템플릿 변수로 넘겨 한 번만 인코딩되게 합니다.
        return get(uri -> uri.path("/live/ticker/{symbol}")
                .queryParam("period", period)
                .build(symbol), "/live/ticker");
    }

    public Optional<JsonNode> daumIntraday(int minutes) {
        return get(uri -> uri.path("/live/daum-intraday")
                .queryParam("minutes", minutes)
                .build(), "/live/daum-intraday");
    }

    // -------------------------------------------------------------- 호출 공통
    //
    // URI는 **UriBuilder에 맡겨 한 번만 인코딩**합니다.
    //
    // 예전에는 UriComponentsBuilder…toUriString()으로 만든 문자열을 그대로
    // RestClient에 넘겼습니다. toUriString()이 이미 인코딩을 하고, RestClient가
    // 받은 문자열을 URI 템플릿으로 보고 또 인코딩해서 **두 번** 인코딩됐습니다.
    //
    //   보낸 값 : investor=기관
    //   1차     : investor=%EA%B8%B0%EA%B4%80
    //   2차     : investor=%25EA%25B8%25B0%25EA%25B4%2580   ← 수집기가 받은 것
    //
    // 수집기는 '기관' 대신 '%EA%B8%B0%EA%B4%80'이라는 글자를 받아 "미지원
    // 투자주체"로 처리했고, 화면에는 "수급 데이터를 얻지 못했습니다"만 떴습니다.
    // ASCII만 쓰는 호출은 멀쩡해서 한글이 들어가는 조합에서만 터졌습니다.
    private Optional<JsonNode> get(Function<UriBuilder, URI> uriFunction, String label) {
        try {
            return Optional.ofNullable(
                    client.get().uri(uriFunction).retrieve().body(JsonNode.class));
        } catch (Exception e) {
            log.warn("수집기 호출 실패 (GET {}): {}", label, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<JsonNode> get(String uri) {
        return get(builder -> builder.path(uri).build(), uri);
    }

    private Optional<JsonNode> post(Function<UriBuilder, URI> uriFunction,
                                    Object body,
                                    String label) {
        try {
            return Optional.ofNullable(
                    client.post().uri(uriFunction).body(body).retrieve().body(JsonNode.class));
        } catch (Exception e) {
            log.warn("수집기 호출 실패 (POST {}): {}", label, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<JsonNode> post(String uri, Object body) {
        return post(builder -> builder.path(uri).build(), body, uri);
    }
}

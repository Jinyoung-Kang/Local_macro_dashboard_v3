package com.macrodash.web;

import com.macrodash.collector.CollectorClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 🔌 토스증권 API 테스트 — 수집기가 대신 호출한 결과를 그대로 전달합니다.
 *
 * <p>경로는 화면 호환을 위해 {@code /api/ai/toss/*}를 유지합니다. 예전에는 AI 리포트와
 * 같은 컨트롤러에 있었지만 AI와는 관계가 없습니다(연결 테스트 화면에 함께 있을 뿐).
 */
@RestController
@RequestMapping("/api/ai/toss")
public class TossController {

    private final CollectorClient collector;

    public TossController(CollectorClient collector) {
        this.collector = collector;
    }

    @GetMapping("/diagnostics")
    public Map<String, Object> tossDiagnostics() {
        return unwrap(collector.tossDiagnostics(),
                "수집기에 연결하지 못했습니다. 토스 진단은 수집기가 수행합니다.");
    }

    @GetMapping("/exchange-rate")
    public Map<String, Object> tossExchangeRate(@RequestParam(defaultValue = "USD") String base,
                                                @RequestParam(defaultValue = "KRW") String quote) {
        return unwrap(collector.tossExchangeRate(base, quote), "수집기에 연결하지 못했습니다.");
    }

    @GetMapping("/indices")
    public Map<String, Object> tossIndices(@RequestParam String symbols) {
        return unwrap(collector.tossIndices(symbols), "수집기에 연결하지 못했습니다.");
    }

    private Map<String, Object> unwrap(Optional<JsonNode> payload, String failureMessage) {
        if (payload.isEmpty()) {
            return Map.of("ok", false, "message", failureMessage);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        payload.get().properties().forEach(entry -> out.put(entry.getKey(), entry.getValue()));
        return out;
    }
}

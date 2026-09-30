package com.macrodash.feature.toss;

import com.macrodash.collector.CollectorClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 토스증권 API 연결 테스트 — 수집기가 대신 호출한 결과를 그대로 전달합니다.
 *
 * <p>토스 키는 수집기에만 있으므로 백엔드는 결과를 옮겨 줄 뿐입니다. 수집기에 닿지 못하면
 * {@code ok=false}와 안내 문구를 돌려줍니다(화면이 빈 칸 대신 이유를 보여 줄 수 있게).
 */
@Service
public class TossService {

    private final CollectorClient collector;

    public TossService(CollectorClient collector) {
        this.collector = collector;
    }

    public Map<String, Object> diagnostics() {
        return unwrap(collector.tossDiagnostics(),
                "수집기에 연결하지 못했습니다. 토스 진단은 수집기가 수행합니다.");
    }

    public Map<String, Object> exchangeRate(String base, String quote) {
        return unwrap(collector.tossExchangeRate(base, quote), "수집기에 연결하지 못했습니다.");
    }

    public Map<String, Object> indices(String symbols) {
        return unwrap(collector.tossIndices(symbols), "수집기에 연결하지 못했습니다.");
    }

    private static Map<String, Object> unwrap(Optional<JsonNode> payload, String failureMessage) {
        if (payload.isEmpty()) {
            return Map.of("ok", false, "message", failureMessage);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        payload.get().properties().forEach(entry -> out.put(entry.getKey(), entry.getValue()));
        return out;
    }
}

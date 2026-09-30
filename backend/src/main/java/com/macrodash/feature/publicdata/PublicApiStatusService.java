package com.macrodash.feature.publicdata;

import com.macrodash.collector.CollectorClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 국내 공공 API(공공데이터포털·Open DART) 연결 진단.
 *
 * <p>진단은 키를 가진 수집기가 API당 1회씩 실제로 호출해 수행합니다. 응답에는 키가 없고,
 * 설정 여부와 키가 지워진 실패 사유만 있습니다.
 */
@Service
public class PublicApiStatusService {

    private final CollectorClient collector;

    public PublicApiStatusService(CollectorClient collector) {
        this.collector = collector;
    }

    /** @return {@code available} + 수집기 진단 결과 필드. 수집기에 못 닿으면 {@code available=false, message} */
    public Map<String, Object> diagnostics() {
        Optional<JsonNode> payload = collector.publicApiDiagnostics();
        Map<String, Object> out = new LinkedHashMap<>();
        if (payload.isEmpty()) {
            out.put("available", false);
            out.put("message", "수집기에 연결하지 못했습니다. 진단은 수집기가 수행합니다.");
            return out;
        }
        out.put("available", true);
        payload.get().properties().forEach(entry -> out.put(entry.getKey(), entry.getValue()));
        return out;
    }
}

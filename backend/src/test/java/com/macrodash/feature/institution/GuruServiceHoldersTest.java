package com.macrodash.feature.institution;

import com.macrodash.read.StoreReader;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 보유 기관 검색(GET /api/guru/holders)은 일치 행을 비중 상위 200개까지만 돌려준다(QA-133).
 *
 * <p>예전에는 일치하는 행을 모두 돌려줘, 한 글자 검색(q=C)이 12개 기관 최신 분기 보유 약 1만 6천 행 가운데 1만 4,748행
 * (JSON 3MB)이 되고 브라우저가 그 행을 전부 그렸습니다. 이제 비중 순으로 잘라 내고, 잘랐다는 사실과 원래 일치 건수를
 * {@code truncated}·{@code totalMatches}로 함께 내려보냅니다 — 잘린 목록을 전부인 것처럼 보이지 않게.
 */
class GuruServiceHoldersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 기관 하나의 최신 분기 보유 — 이름 "{prefix} {i}", 비중은 {@code weightBase + i}. */
    private static ObjectNode filing(String prefix, int count, double weightBase) {
        ObjectNode filings = MAPPER.createObjectNode();
        ArrayNode holdings = filings.putArray("quarters").addObject()
                .put("reportDate", "2026-06-30").put("filingDate", "2026-08-14")
                .putArray("holdings");
        for (int i = 0; i < count; i++) {
            holdings.addObject().put("name", prefix + " " + i).put("cusip", String.format("%09d", i))
                    .put("weight", weightBase + i).put("value", 1_000.0 * (i + 1));
        }
        holdings.addObject().put("name", "BETA HOLDINGS INC").put("cusip", "BETA00001")
                .put("weight", 0.5).put("value", 500.0);
        return filings;
    }

    /** 앞의 두 기관에만 13F 저장본이 있는 저장소. */
    private static StoreReader store(ObjectNode first, ObjectNode second) {
        StoreReader store = mock(StoreReader.class);
        when(store.read(anyString(), anyLong(), anyString())).thenReturn(Optional.empty());
        List<String> ciks = Institutions.ciks();
        String firstName = Datasets.sec13f(ciks.get(0), Datasets.MAX_TRACKED_QUARTERS);
        String secondName = Datasets.sec13f(ciks.get(1), Datasets.MAX_TRACKED_QUARTERS);
        when(store.read(eq(firstName), anyLong(), anyString()))
                .thenReturn(Optional.of(new Snapshot(firstName, first, "json", "ok", null, Instant.now())));
        when(store.read(eq(secondName), anyLong(), anyString()))
                .thenReturn(Optional.of(new Snapshot(secondName, second, "json", "ok", null, Instant.now())));
        return store;
    }

    @Test
    @DisplayName("QA-133 일치 행이 200개를 넘으면 비중 상위 200행만 돌려주고, 원래 일치 건수와 잘렸다는 표시를 함께 준다")
    void cutsToTopRowsByWeight() {
        // 두 기관 합쳐 'ALPHA CORP'가 들어간 행 250개(150 + 100) — 비중은 첫 기관 0~149, 둘째 기관 1000~1099.
        GuruService guru = new GuruService(store(filing("ALPHA CORP", 150, 0), filing("ALPHA CORP", 100, 1000)));

        Map<String, Object> response = guru.holders("alpha");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("rows");
        assertThat(rows.size()).as("돌려준 행 수").isEqualTo(200);
        assertThat(response.get("totalMatches")).as("totalMatches").isEqualTo(250);
        assertThat(response.get("truncated")).as("truncated").isEqualTo(true);
        assertThat(response.get("available")).isEqualTo(true);
        // 비중이 큰 행부터 — 잘려 나간 것은 비중이 가장 작은 50행(첫 기관의 0~49)입니다.
        assertThat(rows.get(0).get("weight")).isEqualTo(1099.0);
        assertThat(rows.get(199).get("weight")).isEqualTo(50.0);
        assertThat(rows.get(0).get("institution")).isEqualTo(Institutions.ALL.get(1).get("name"));
    }

    @Test
    @DisplayName("QA-133 일치 행이 200개 이하면 모두 돌려주고 잘리지 않았다고 적는다 — 없을 때도 일치 0건")
    void smallResultIsNotTruncated() {
        GuruService guru = new GuruService(store(filing("ALPHA CORP", 150, 0), filing("ALPHA CORP", 100, 1000)));

        Map<String, Object> beta = guru.holders("BETA");
        assertThat(((List<?>) beta.get("rows")).size()).as("돌려준 행 수").isEqualTo(2);
        assertThat(beta.get("totalMatches")).as("totalMatches").isEqualTo(2);
        assertThat(beta.get("truncated")).as("truncated").isEqualTo(false);

        Map<String, Object> none = guru.holders("NO SUCH NAME");
        assertThat((List<?>) none.get("rows")).isEmpty();
        assertThat(none.get("totalMatches")).as("totalMatches").isEqualTo(0);
        assertThat(none.get("truncated")).as("truncated").isEqualTo(false);
        assertThat(none.get("available")).isEqualTo(false);
    }

    @Test
    @DisplayName("QA-133 응답 캐시가 요청끼리 나눠 쓰는 보유 목록은 바꿀 수 없고, 검색은 그 목록을 그대로 둔 채 행을 새로 만든다")
    void indexIsReadOnlyAndSharedAcrossQueries() {
        GuruService guru = new GuruService(store(filing("ALPHA CORP", 150, 0), filing("ALPHA CORP", 100, 1000)));
        List<GuruService.HoldingRow> index = guru.holdersIndex();

        assertThat(index).hasSize(252);
        assertThatThrownBy(() -> index.add(index.get(0))).isInstanceOf(UnsupportedOperationException.class);
        // 같은 목록으로 서로 다른 검색어를 찾아도 서로 영향을 주지 않습니다(목록을 제자리에서 정렬하거나 걸러 내지 않음).
        assertThat(guru.holders(index, "BETA").get("totalMatches")).isEqualTo(2);
        assertThat(guru.holders(index, "ALPHA").get("totalMatches")).isEqualTo(250);
        assertThat(guru.holders(index, "BETA").get("totalMatches")).isEqualTo(2);
        assertThat(index).hasSize(252);
    }
}

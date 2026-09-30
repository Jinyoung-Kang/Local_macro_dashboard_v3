package com.macrodash.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사람이 정해 둔 목록 순서가 응답에 그대로 나가는지.
 *
 * <p>자산군·섹터·COT 자산 목록은 주식 → 채권 → 원자재 → 통화처럼 순서를 정해 {@code LinkedHashMap}에
 * 담았지만, 마지막에 {@code Map.copyOf}로 감싸 순서가 사라졌습니다. {@code Map.copyOf}의 반복 순서는
 * JVM을 켤 때마다 무작위로 정해지므로, 백엔드를 다시 켤 때마다 COT 화면의 자산 선택 목록 순서가
 * 바뀌었습니다(같은 jar를 두 번 띄워 비교해 확인).
 */
class CuratedOrderTest {

    @Test
    @DisplayName("COT 자산 목록은 정해 둔 순서 그대로 나간다")
    @SuppressWarnings("unchecked")
    void cotAssetsKeepCuratedOrder() {
        CotService service = new CotService(null, null);

        List<Map<String, String>> assets = (List<Map<String, String>>) service.assetList().get("assets");

        assertThat(assets).extracting(asset -> asset.get("name")).containsExactly(
                "S&P 500 E-Mini", "NASDAQ 100 E-Mini", "미국 국채 10년물", "달러 인덱스", "WTI 원유", "금");
    }

    @Test
    @DisplayName("자산군·섹터 ETF 목록은 정해 둔 순서 그대로다")
    void etfUniversesKeepCuratedOrder() {
        assertThat(SectorService.ASSET_CLASS_ETFS.keySet()).containsExactly(
                "SPY", "QQQ", "IWM", "EEM", "TLT", "IEF", "SHY", "GLD", "USO", "DBA", "UUP");
        assertThat(SectorService.SECTOR_ETFS.keySet()).containsExactly(
                "XLK", "XLC", "XLY", "XLI", "XLF", "XLB", "XLE", "XLV", "XLP", "XLU", "XLRE");
    }
}

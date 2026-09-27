package com.macrodash.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 수집기(Python)와 백엔드(Java)가 같은 데이터셋 이름을 쓰는지 대조합니다.
 *
 * <p>한쪽이 오타를 내면 "수집은 되는데 화면에는 안 보이는" 버그가 생기고,
 * 증상만으로는 원인을 찾기 어렵습니다. 이 테스트가 그 어긋남을 빌드 단계에서
 * 잡습니다. (구버전이 datasets.py를 단일 출처로 둔 것과 같은 목적입니다.)
 */
class DatasetsParityTest {

    private static final Path CATALOG = Path.of("../collector/app/catalog.py");

    @Test
    @DisplayName("스냅샷 이름이 catalog.py와 일치한다")
    void snapshotNamesMatch() throws IOException {
        assumeTrue(Files.exists(CATALOG), "collector/app/catalog.py를 찾을 수 없습니다");
        String source = Files.readString(CATALOG);

        assertThat(constant(source, "SNAP_MACRO_COLLECTED"))
                .isEqualTo(Datasets.SNAP_MACRO_COLLECTED);
        assertThat(constant(source, "SNAP_SCRAPER_MARKETS"))
                .isEqualTo(Datasets.SNAP_SCRAPER_MARKETS);
        assertThat(constant(source, "SNAP_FED_LIQUIDITY"))
                .isEqualTo(Datasets.SNAP_FED_LIQUIDITY);
        assertThat(constant(source, "SNAP_KRX_FUTURES"))
                .isEqualTo(Datasets.SNAP_KRX_FUTURES);
        assertThat(constant(source, "SNAP_SECTOR_HISTORY"))
                .isEqualTo(Datasets.SNAP_SECTOR_HISTORY);
        assertThat(constant(source, "SNAP_COT_HISTORY"))
                .isEqualTo(Datasets.SNAP_COT_HISTORY);
        assertThat(constant(source, "SNAP_FX_HISTORY"))
                .isEqualTo(Datasets.SNAP_FX_HISTORY);
        assertThat(constant(source, "SNAP_EQUITY_HISTORY"))
                .isEqualTo(Datasets.SNAP_EQUITY_HISTORY);
        assertThat(constant(source, "SNAP_KR_HOLIDAYS"))
                .isEqualTo(Datasets.SNAP_KR_HOLIDAYS);
        assertThat(constant(source, "SNAP_DART_FUNDAMENTALS"))
                .isEqualTo(Datasets.SNAP_DART_FUNDAMENTALS);
    }

    @Test
    @DisplayName("누적 이력 데이터셋 이름이 일치한다")
    void accumulationNamesMatch() throws IOException {
        assumeTrue(Files.exists(CATALOG), "collector/app/catalog.py를 찾을 수 없습니다");
        String source = Files.readString(CATALOG);

        assertThat(constant(source, "TS_FRED")).isEqualTo(Datasets.TS_FRED);
        assertThat(constant(source, "TS_KRX_FUTURES")).isEqualTo(Datasets.TS_KRX_FUTURES);
        assertThat(constant(source, "TS_LIQUIDITY")).isEqualTo(Datasets.TS_LIQUIDITY);
        assertThat(constant(source, "OBS_RADAR")).isEqualTo(Datasets.OBS_RADAR);
        assertThat(constant(source, "OBS_FSC_PRICE")).isEqualTo(Datasets.OBS_FSC_PRICE);
        assertThat(constant(source, "TS_FSC_MARKET")).isEqualTo(Datasets.TS_FSC_MARKET);
        assertThat(constant(source, "SNAP_FSC_PRICES_META")).isEqualTo(Datasets.SNAP_FSC_PRICES_META);
        assertThat(constant(source, "SNAP_TOSS_MARKET_FLOWS")).isEqualTo(Datasets.SNAP_TOSS_MARKET_FLOWS);
        assertThat(constant(source, "OBS_TOSS_STOCK_FLOW")).isEqualTo(Datasets.OBS_TOSS_STOCK_FLOW);
        assertThat(constant(source, "SNAP_TOSS_RADAR_UNIVERSE")).isEqualTo(Datasets.SNAP_TOSS_RADAR_UNIVERSE);
    }

    @Test
    @DisplayName("키 생성 규칙이 같은 문자열을 만든다")
    void keyBuildersMatch() {
        // catalog.py의 f-string과 같은 형태여야 합니다.
        assertThat(Datasets.fredSeries("T10Y3M")).isEqualTo("fred.series.T10Y3M");
        assertThat(Datasets.sec13f("0001067983", 8)).isEqualTo("sec.13f.0001067983.q8");
        assertThat(Datasets.cotContract("13874A", 166)).isEqualTo("cot.contract.13874A.l166");
        assertThat(Datasets.tickerHistory("^VIX", "5y")).isEqualTo("ticker.VIX.5y");
        assertThat(Datasets.tickerHistory("069500.KS", "1y")).isEqualTo("ticker.069500_KS.1y");
        assertThat(Datasets.daumFuturesTrend(25))
                .isEqualTo("krx.daum_futures_trend.d25.CONTRACT");
        assertThat(Datasets.radarScanner("KOSPI", "외국인", "순매수", "TODAY"))
                .isEqualTo("radar.scanner.KOSPI.외국인.순매수.TODAY");
    }

    @Test
    @DisplayName("신선도 기준이 catalog.py와 일치한다")
    void freshnessMatches() throws IOException {
        assumeTrue(Files.exists(CATALOG), "collector/app/catalog.py를 찾을 수 없습니다");
        String source = Files.readString(CATALOG);

        assertThat(numericConstant(source, "MAX_AGE_REALTIME")).isEqualTo(Datasets.MAX_AGE_REALTIME);
        assertThat(numericConstant(source, "MAX_AGE_DAILY")).isEqualTo(Datasets.MAX_AGE_DAILY);
        assertThat(numericConstant(source, "MAX_AGE_SLOW")).isEqualTo(Datasets.MAX_AGE_SLOW);
    }

    private String constant(String source, String name) {
        Matcher matcher = Pattern.compile(name + "\\s*=\\s*\"([^\"]+)\"").matcher(source);
        assertThat(matcher.find())
                .as("catalog.py에서 %s를 찾지 못했습니다", name)
                .isTrue();
        return matcher.group(1);
    }

    /** {@code MAX_AGE_DAILY = 6 * 60 * 60} 같은 곱셈 표현식을 계산합니다. */
    private long numericConstant(String source, String name) {
        Matcher matcher = Pattern.compile(name + "\\s*=\\s*([0-9 *]+)").matcher(source);
        assertThat(matcher.find())
                .as("catalog.py에서 %s를 찾지 못했습니다", name)
                .isTrue();

        long product = 1;
        for (String part : matcher.group(1).trim().split("\\*")) {
            product *= Long.parseLong(part.trim());
        }
        return product;
    }
}

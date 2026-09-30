package com.macrodash.feature.insight;

import com.macrodash.Kst;
import com.macrodash.analytics.Correlation;
import com.macrodash.support.Json;
import com.macrodash.analytics.Regime;
import com.macrodash.analytics.SeriesMath;
import com.macrodash.store.Datasets;
import com.macrodash.store.Snapshot;
import com.macrodash.store.StoreReader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 🔗 지표 상관관계 · 🧭 시장 국면.
 *
 * <p>화면이 이미 보여 주는 값들을 <b>서로 엮어</b> 읽는 계층입니다. 새 데이터를
 * 받아 오지 않고, 저장된 스냅샷만 조합합니다 — 그래서 수집이 멈춰 있어도
 * 사라지지 않고, 화면의 숫자와 절대 어긋나지 않습니다.
 */
@Service
public class AnalyticsService {

    /** 상관 분석에 쓸 수 있는 계열 하나의 정의. */
    public record SeriesRef(String id, String label, String group, String unit, String source) {
    }

    /** 저장본에서 계열을 꺼내는 방법. */
    private interface Loader {
        NavigableMap<LocalDate, Double> load();
    }

    private final StoreReader store;

    public AnalyticsService(StoreReader store) {
        this.store = store;
    }

    // =========================================================== 계열 목록
    /**
     * 비교할 수 있는 계열 목록.
     *
     * <p>이미 수집하고 있는 저장본만 씁니다. 여기에 없는 지표를 넣으려면 먼저
     * 수집기에 태스크가 있어야 합니다 — 화면에만 추가하면 "왜 항상 비어 있지?"가
     * 됩니다.
     */
    public List<SeriesRef> catalog() {
        List<SeriesRef> list = new ArrayList<>();
        list.add(new SeriesRef("fred:DGS10", "미국채 10년물", "금리", "%", "FRED (일간 확정치)"));
        list.add(new SeriesRef("fred:DGS2", "미국채 2년물", "금리", "%", "FRED (일간 확정치)"));
        list.add(new SeriesRef("fred:T10Y3M", "장단기 금리차 10Y-3M", "금리", "%p", "FRED (일간 확정치)"));
        list.add(new SeriesRef("fred:DFII10", "10년 실질금리 (TIPS)", "금리", "%", "FRED (일간 확정치)"));
        list.add(new SeriesRef("fred:T10YIE", "10년 기대인플레 (BEI)", "금리", "%", "FRED (일간 확정치)"));
        list.add(new SeriesRef("fred:BAMLH0A0HYM2", "하이일드 스프레드", "신용", "%p", "FRED (일간 확정치)"));
        list.add(new SeriesRef("fred:BAMLC0A0CM", "투자등급(IG) 스프레드", "신용", "%p", "FRED (일간 확정치)"));
        list.add(new SeriesRef("fred:NFCI", "금융상황지수 (NFCI)", "금융상황", "", "FRED (주간)"));
        list.add(new SeriesRef("fred:STLFSI4", "금융스트레스 (STLFSI4)", "금융상황", "pt", "FRED (주간)"));
        list.add(new SeriesRef("liquidity:net", "연준 순유동성", "유동성", "조 달러", "FRED 조합 (WALCL−TGA−RRP)"));
        list.add(new SeriesRef("ticker:VIX", "CBOE VIX", "변동성", "pt", "Yahoo Finance ^VIX 종가"));
        list.add(new SeriesRef("etf:SPY", "S&P 500 (SPY)", "주식", "$", "Yahoo Finance ETF 종가"));
        list.add(new SeriesRef("etf:QQQ", "나스닥 100 (QQQ)", "주식", "$", "Yahoo Finance ETF 종가"));
        list.add(new SeriesRef("etf:IWM", "러셀 2000 (IWM)", "주식", "$", "Yahoo Finance ETF 종가"));
        list.add(new SeriesRef("etf:TLT", "미국 장기국채 (TLT)", "채권", "$", "Yahoo Finance ETF 종가"));
        list.add(new SeriesRef("etf:GLD", "금 (GLD)", "원자재", "$", "Yahoo Finance ETF 종가"));
        list.add(new SeriesRef("etf:USO", "WTI 원유 (USO)", "원자재", "$", "Yahoo Finance ETF 종가"));
        list.add(new SeriesRef("etf:UUP", "달러 인덱스 (UUP)", "통화", "$", "Yahoo Finance ETF 종가"));
        list.add(new SeriesRef("etf:EEM", "신흥국 주식 (EEM)", "주식", "$", "Yahoo Finance ETF 종가"));
        list.add(new SeriesRef("krx:futures", "KOSPI200 선물 종가", "국내", "pt", "KRX Open API 일별 마감"));
        return list;
    }

    // ====================================================== 🔗 상관관계
    /**
     * 두 계열의 상관관계.
     *
     * @param mode "change"(기본, 변화끼리) 또는 "level"(수준끼리 — 허위 상관 주의)
     */
    public Map<String, Object> correlation(String xId, String yId, int window, int years,
                                           String mode) {
        Map<String, Object> out = new LinkedHashMap<>();
        SeriesRef x = find(xId);
        SeriesRef y = find(yId);
        out.put("x", x);
        out.put("y", y);
        out.put("mode", "level".equals(mode) ? "level" : "change");
        out.put("window", Math.max(5, window));

        if (x == null || y == null) {
            out.put("available", false);
            out.put("message", "알 수 없는 계열입니다: " + (x == null ? xId : yId));
            return out;
        }
        if (xId.equals(yId)) {
            out.put("available", false);
            out.put("message", "같은 계열끼리는 비교하지 않습니다(상관계수는 항상 1입니다).");
            return out;
        }

        LocalDate cutoff = Kst.yearsAgo(Math.max(1, years));
        NavigableMap<LocalDate, Double> left = sliceFrom(series(xId), cutoff);
        NavigableMap<LocalDate, Double> right = sliceFrom(series(yId), cutoff);

        if (left.isEmpty() || right.isEmpty()) {
            out.put("available", false);
            out.put("message", "두 계열 중 하나의 저장본이 없습니다. "
                    + "🗄️ 데이터 저장소 상태에서 해당 수집을 먼저 실행하세요.");
            return out;
        }

        Correlation.Aligned aligned = Correlation.align(left, right);
        boolean asChange = !"level".equals(mode);
        Correlation.Aligned used = asChange ? Correlation.toChanges(aligned) : aligned;

        int windowSize = Math.max(5, window);
        Double overall = Correlation.pearson(used.x(), used.y());
        List<Double> rolling = Correlation.rolling(used.x(), used.y(), windowSize);

        List<Map<String, Object>> rollingPoints = new ArrayList<>();
        for (int i = 0; i < used.size(); i++) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", used.dates().get(i).toString());
            point.put("value", i < rolling.size() ? rolling.get(i) : null);
            rollingPoints.add(point);
        }

        List<Map<String, Object>> scatter = new ArrayList<>();
        for (int i = 0; i < used.size(); i++) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", used.dates().get(i).toString());
            point.put("x", used.x()[i]);
            point.put("y", used.y()[i]);
            scatter.add(point);
        }

        out.put("available", overall != null);
        out.put("overall", overall);
        out.put("samples", used.size());
        out.put("rolling", rollingPoints);
        out.put("scatter", scatter);
        out.put("firstDate", used.dates().isEmpty() ? null : used.dates().get(0).toString());
        out.put("lastDate", used.dates().isEmpty()
                ? null : used.dates().get(used.dates().size() - 1).toString());

        List<String> notes = new ArrayList<>();
        if (!asChange) {
            notes.add("수준(level)끼리의 상관입니다. 두 계열이 각자 추세만 갖고 있어도 "
                    + "값이 크게 나옵니다(허위 상관). 인과로 읽지 마세요.");
        }
        if (used.size() < 30) {
            notes.add("표본이 %d개뿐입니다. 발표 주기가 다른 계열(주간 NFCI 등)을 비교하면 "
                    .formatted(used.size()) + "겹치는 날짜만 남아 표본이 크게 줄어듭니다.");
        }
        notes.add("상관은 같이 움직였다는 뜻일 뿐, 어느 쪽이 원인인지는 말해 주지 않습니다.");
        out.put("notes", notes);
        return out;
    }

    // ========================================================= 🧭 국면
    /** 지금 국면 + 최근 이력. */
    public Map<String, Object> regime(int years) {
        Map<String, Object> out = new LinkedHashMap<>();

        NavigableMap<LocalDate, Double> curve = series("fred:T10Y3M");
        NavigableMap<LocalDate, Double> nfci = series("fred:NFCI");
        NavigableMap<LocalDate, Double> hyOas = series("fred:BAMLH0A0HYM2");
        NavigableMap<LocalDate, Double> netLiquidity = series("liquidity:net");

        Regime.Verdict verdict = Regime.classify(
                lastValue(curve), lastValue(nfci), lastValue(hyOas),
                change4w(netLiquidity));

        out.put("verdict", verdict);
        out.put("available", verdict.known());
        out.put("asOf", latestCommonDate(curve, nfci, hyOas, netLiquidity));

        // 최근 이력 — 같은 규칙을 과거 날짜에 그대로 적용합니다.
        List<Map<String, Object>> timeline = new ArrayList<>();
        LocalDate cutoff = Kst.yearsAgo(Math.max(1, years));
        for (LocalDate date : weeklyDates(curve, cutoff)) {
            Regime.Verdict past = Regime.classify(
                    valueAsOf(curve, date), valueAsOf(nfci, date), valueAsOf(hyOas, date),
                    change4wAsOf(netLiquidity, date));
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", date.toString());
            point.put("code", past.code());
            point.put("label", past.label());
            timeline.add(point);
        }
        out.put("timeline", timeline);
        out.put("episodes", episodes(timeline));
        out.put("note", "임계치는 역사적 분포에 근거한 참고치이며 투자 판단의 근거가 아닙니다. "
                + "입력 지표가 하나라도 없으면 판정하지 않습니다.");
        return out;
    }

    /** 타임라인을 같은 국면이 이어진 구간으로 묶습니다. */
    private List<Map<String, Object>> episodes(List<Map<String, Object>> timeline) {
        List<Map<String, Object>> out = new ArrayList<>();
        String currentCode = null;
        String currentLabel = null;
        String start = null;
        String previousDate = null;
        int weeks = 0;

        for (Map<String, Object> point : timeline) {
            String code = String.valueOf(point.get("code"));
            if (!code.equals(currentCode)) {
                if (currentCode != null) {
                    out.add(episode(currentCode, currentLabel, start, previousDate, weeks));
                }
                currentCode = code;
                currentLabel = String.valueOf(point.get("label"));
                start = String.valueOf(point.get("date"));
                weeks = 0;
            }
            weeks++;
            previousDate = String.valueOf(point.get("date"));
        }
        if (currentCode != null) {
            out.add(episode(currentCode, currentLabel, start, previousDate, weeks));
        }
        // 최근 구간이 위로 오게 뒤집습니다.
        java.util.Collections.reverse(out);
        return out;
    }

    private Map<String, Object> episode(String code, String label, String start, String end,
                                        int weeks) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", code);
        entry.put("label", label);
        entry.put("start", start);
        entry.put("end", end);
        entry.put("weeks", weeks);
        return entry;
    }

    // ================================================== 계열 읽기 헬퍼
    public SeriesRef find(String id) {
        return catalog().stream().filter(ref -> ref.id().equals(id)).findFirst().orElse(null);
    }

    /** 계열 id → 날짜별 값. 없으면 빈 맵입니다(0으로 채우지 않습니다). */
    public NavigableMap<LocalDate, Double> series(String id) {
        if (id == null) {
            return new TreeMap<>();
        }
        String[] parts = id.split(":", 2);
        if (parts.length != 2) {
            return new TreeMap<>();
        }
        return switch (parts[0]) {
            case "fred" -> fredSeries(parts[1]);
            case "etf" -> etfSeries(parts[1]);
            case "ticker" -> tickerSeries(parts[1]);
            case "liquidity" -> netLiquiditySeries();
            case "krx" -> krxFuturesSeries();
            default -> new TreeMap<>();
        };
    }

    private NavigableMap<LocalDate, Double> fredSeries(String seriesId) {
        Optional<Snapshot> snapshot = store.read(
                Datasets.fredSeries(seriesId), Datasets.MAX_AGE_DAILY, "fred_series");
        return points(snapshot);
    }

    private NavigableMap<LocalDate, Double> tickerSeries(String symbol) {
        Optional<Snapshot> snapshot = store.read(
                Datasets.tickerHistory("^" + symbol, Datasets.VOLATILITY_STORE_PERIOD),
                Datasets.MAX_AGE_DAILY, "volatility_history");
        return points(snapshot);
    }

    private NavigableMap<LocalDate, Double> points(Optional<Snapshot> snapshot) {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        if (snapshot.isEmpty() || snapshot.get().payload() == null) {
            return out;
        }
        List<Double> values = Json.pointValues(snapshot.get().payload());
        List<LocalDate> dates = Json.pointDates(snapshot.get().payload());
        for (int i = 0; i < Math.min(values.size(), dates.size()); i++) {
            out.put(dates.get(i), values.get(i));
        }
        return out;
    }

    /** 섹터 화면이 쓰는 ETF 종가 저장본에서 한 종목만 꺼냅니다. */
    public NavigableMap<LocalDate, Double> etfSeries(String symbol) {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        Optional<Snapshot> snapshot = store.read(
                Datasets.SNAP_SECTOR_HISTORY, Datasets.MAX_AGE_DAILY, "sector_history");
        if (snapshot.isEmpty() || snapshot.get().payload() == null) {
            return out;
        }
        JsonNode tickers = Json.child(snapshot.get().payload(), "tickers");
        JsonNode series = tickers == null ? null : tickers.get(symbol);
        if (series == null) {
            return out;
        }
        JsonNode dates = Json.child(series, "dates");
        JsonNode closes = Json.child(series, "close");
        if (dates == null || closes == null || !dates.isArray() || !closes.isArray()) {
            return out;
        }
        int size = Math.min(dates.size(), closes.size());
        for (int i = 0; i < size; i++) {
            LocalDate date = Json.parseDate(dates.get(i).asString(""));
            JsonNode close = closes.get(i);
            if (date != null && close != null && close.isNumber()) {
                out.put(date, close.asDouble());
            }
        }
        return out;
    }

    /**
     * 연준 순유동성 (WALCL − TGA − ON RRP).
     *
     * <p>저장본을 직접 읽습니다. LiquidityService를 거치면 화면용으로 가공된
     * 모양에 묶이고, 그쪽 응답 구조가 바뀔 때마다 여기가 조용히 비게 됩니다.
     */
    private NavigableMap<LocalDate, Double> netLiquiditySeries() {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        Optional<Snapshot> snapshot = store.read(
                Datasets.SNAP_FED_LIQUIDITY, Datasets.MAX_AGE_DAILY, "fed_liquidity");
        if (snapshot.isEmpty() || snapshot.get().payload() == null) {
            return out;
        }
        for (JsonNode row : Json.array(snapshot.get().payload(), "rows")) {
            LocalDate date = Json.parseDate(Json.asText(row, "date"));
            Double value = Json.asDouble(row, "netLiquidityT");
            if (date != null && value != null) {
                out.put(date, value);
            }
        }
        return out;
    }

    private NavigableMap<LocalDate, Double> krxFuturesSeries() {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        Optional<Snapshot> snapshot = store.read(
                Datasets.SNAP_KRX_FUTURES, Datasets.MAX_AGE_DAILY, "krx_futures");
        if (snapshot.isEmpty() || snapshot.get().payload() == null) {
            return out;
        }
        for (JsonNode row : Json.array(snapshot.get().payload(), "rows")) {
            LocalDate date = Json.parseDate(Json.asText(row, "date"));
            Double close = Json.asDouble(row, "futuresClose");
            if (date != null && close != null) {
                out.put(date, close);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 작은 도구
    private NavigableMap<LocalDate, Double> sliceFrom(NavigableMap<LocalDate, Double> series,
                                                      LocalDate cutoff) {
        return new TreeMap<>(series.tailMap(cutoff, true));
    }

    private Double lastValue(NavigableMap<LocalDate, Double> series) {
        return series.isEmpty() ? null : series.lastEntry().getValue();
    }

    private Double valueAsOf(NavigableMap<LocalDate, Double> series, LocalDate date) {
        if (series.isEmpty()) {
            return null;
        }
        var entry = series.floorEntry(date);
        return entry == null ? null : entry.getValue();
    }

    /** 순유동성 4주 변화. 4주 전 값이 없으면 null입니다. */
    private Double change4w(NavigableMap<LocalDate, Double> series) {
        return series.isEmpty() ? null : change4wAsOf(series, series.lastKey());
    }

    private Double change4wAsOf(NavigableMap<LocalDate, Double> series, LocalDate date) {
        Double now = valueAsOf(series, date);
        Double past = valueAsOf(series, date.minusWeeks(4));
        return SeriesMath.difference(now, past);
    }

    /** 주 단위 날짜 목록 (금리차 계열을 기준으로 잡습니다). */
    private List<LocalDate> weeklyDates(NavigableMap<LocalDate, Double> base, LocalDate cutoff) {
        List<LocalDate> out = new ArrayList<>();
        if (base.isEmpty()) {
            return out;
        }
        LocalDate date = base.firstKey().isAfter(cutoff) ? base.firstKey() : cutoff;
        LocalDate last = base.lastKey();
        while (!date.isAfter(last)) {
            out.add(date);
            date = date.plusWeeks(1);
        }
        return out;
    }

    @SafeVarargs
    private String latestCommonDate(NavigableMap<LocalDate, Double>... series) {
        LocalDate earliestLast = null;
        for (NavigableMap<LocalDate, Double> entry : series) {
            if (entry.isEmpty()) {
                return null;
            }
            LocalDate last = entry.lastKey();
            if (earliestLast == null || last.isBefore(earliestLast)) {
                earliestLast = last;
            }
        }
        return earliestLast == null ? null : earliestLast.toString();
    }
}

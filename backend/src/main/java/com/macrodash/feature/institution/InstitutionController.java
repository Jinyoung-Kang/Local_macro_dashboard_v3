package com.macrodash.feature.institution;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 📑 기관 13F 포트폴리오 · 🎯 13F 교집합 · 🧬 기관 13F 스타일·위험.
 *
 * <pre>
 *  GET /api/sec13f/*   기관 목록·포트폴리오·교집합·공통 신규 매수
 *  GET /api/guru/*     기관 성격·유사도·보유 기관 검색·위험 (경로는 호환을 위해 guru 유지)
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class InstitutionController {

    private final Sec13FService sec13f;
    private final GuruService guru;

    public InstitutionController(Sec13FService sec13f, GuruService guru) {
        this.sec13f = sec13f;
        this.guru = guru;
    }

    // ------------------------------------------------------- 📑 13F
    @GetMapping("/sec13f/institutions")
    public Map<String, Object> institutions() {
        return Institutions.list();
    }

    @GetMapping("/sec13f/portfolio")
    public Map<String, Object> portfolio(@RequestParam String cik,
                                         @RequestParam(defaultValue = "8") int quarters,
                                         @RequestParam(defaultValue = "30") int topN) {
        return sec13f.portfolio(cik, quarters, topN);
    }

    @GetMapping("/sec13f/consensus")
    public Map<String, Object> consensus(
            @RequestParam(required = false) String ciks,
            @RequestParam(required = false) String reportDate,
            @RequestParam(defaultValue = "2") int minHolders,
            @RequestParam(defaultValue = "30") int topN) {
        return sec13f.consensus(selectedCiks(ciks), reportDate, minHolders, topN);
    }

    /** 🆕 이번 분기에 여러 기관이 함께 새로 담은 종목. */
    @GetMapping("/sec13f/new-buys")
    public Map<String, Object> newBuys(
            @RequestParam(required = false) String ciks,
            @RequestParam(required = false) String reportDate,
            @RequestParam(defaultValue = "3") int minHolders) {
        return sec13f.newBuys(selectedCiks(ciks), reportDate, minHolders);
    }

    // ------------------------------------------------- 🧬 기관 스타일·위험
    /** 기관별 성격 요약 (집중도·유효 종목 수·회전율). */
    @GetMapping("/guru/profiles")
    public Map<String, Object> guruProfiles() {
        return guru.profiles();
    }

    /** 기관 간 유사도 행렬 (겹침 비중 + 코사인). */
    @GetMapping("/guru/similarity")
    public Map<String, Object> guruSimilarity() {
        return guru.similarity();
    }

    /** 이 종목을 누가 들고 있나 (13F 공시 이름 일부로 검색). */
    @GetMapping("/guru/holders")
    public Map<String, Object> guruHolders(@RequestParam(required = false) String q) {
        return guru.holders(q);
    }

    /**
     * 구루 포트폴리오의 위험 지표.
     *
     * <p>13F에는 티커가 없어 이름으로 가격을 찾습니다. 매핑표에 없는 종목은
     * 빠지며, 덮은 비중이 응답의 coverage에 항상 들어 있습니다.
     */
    @GetMapping("/guru/risk")
    public Map<String, Object> guruRisk(
            @RequestParam String cik,
            @RequestParam(defaultValue = "SPY") String benchmark,
            @RequestParam(defaultValue = "1") int years) {
        return guru.risk(cik, benchmark, years);
    }

    /** 쉼표로 구분한 CIK 목록. 비어 있으면 추적하는 기관 전체입니다. */
    private static List<String> selectedCiks(String ciks) {
        return (ciks == null || ciks.isBlank())
                ? Institutions.ciks()
                : Arrays.stream(ciks.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }
}

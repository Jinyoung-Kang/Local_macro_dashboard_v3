package com.macrodash.feature.snapshot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 📋 전체 원본 데이터 — 수집한 대시보드 원본 텍스트(화면 표시·복사용). */
@RestController
@RequestMapping("/api")
public class SnapshotTextController {

    private final SnapshotTextService snapshotText;

    public SnapshotTextController(SnapshotTextService snapshotText) {
        this.snapshotText = snapshotText;
    }

    /**
     * 수집한 전체 대시보드 원본 텍스트 (AI 분석 없음 · 화면 표시/복사용).
     *
     * <p>AI 메뉴의 {@code /api/ai/snapshot-text}와 같은 텍스트지만 경로를 나눠
     * 둡니다. 원본 데이터를 보는 일은 AI 키가 없어도 되는 기능인데, AI 경로
     * 아래에 두면 "AI 기능"으로 읽히기 때문입니다.
     */
    @GetMapping("/snapshot/text")
    public Map<String, Object> snapshotText() {
        return snapshotText.payload();
    }
}

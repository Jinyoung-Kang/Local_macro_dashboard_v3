package com.macrodash.web;

import com.macrodash.service.DataStatusService;
import com.macrodash.service.SnapshotTextService;
import com.macrodash.service.VerificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 🗄️ 데이터 저장소 상태 · 교차 검증 · 📋 전체 원본 데이터 · 헬스체크.
 *
 * <pre>
 *  GET  /api/health            헬스체크 (인증 없음)
 *  GET  /api/status/*          수집 현황·오류 모음·태스크·실행 이력
 *  POST /api/status/*          수동 새로고침·태스크 1건 실행
 *  POST /api/verification      교차 검증
 *  GET  /api/snapshot/text     전체 원본 텍스트
 * </pre>
 *
 * <p>국내 공공 API 진단({@code /api/status/public-apis})은 출처가 같은
 * {@link PublicDataController}에 있습니다.
 */
@RestController
@RequestMapping("/api")
public class StatusController {

    private final DataStatusService status;
    private final VerificationService verification;
    private final SnapshotTextService snapshotText;

    public StatusController(DataStatusService status,
                            VerificationService verification,
                            SnapshotTextService snapshotText) {
        this.status = status;
        this.verification = verification;
        this.snapshotText = snapshotText;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    // --------------------------------------------------- 🗄️ 저장소 상태
    @GetMapping("/status")
    public Map<String, Object> status() {
        return status.status();
    }

    /**
     * ⚠️ 수집 오류·경고 모음 — 지금 실패 중인 태스크, 최근 24시간 실패 이력(같은 사유는 묶음),
     * 누락 데이터셋. {@code text}는 그대로 복사해 붙일 수 있는 형태이고 비밀값은 가려져 있습니다.
     */
    @GetMapping("/status/issues")
    public Map<String, Object> statusIssues() {
        return status.issues();
    }

    @GetMapping("/status/tasks")
    public Map<String, Object> tasks() {
        return status.tasks();
    }

    @GetMapping("/status/history")
    public Map<String, Object> taskHistory(@RequestParam(required = false) String task,
                                           @RequestParam(defaultValue = "40") int limit) {
        return status.taskHistory(task, limit);
    }

    @PostMapping("/status/refresh")
    public Map<String, Object> refresh(@RequestParam(defaultValue = "true") boolean runFast) {
        return status.refresh(runFast);
    }

    /**
     * 태스크 1건 실행 시작 → 202. 끝났는지는 {@code GET /api/status/history?task=…&limit=1}의
     * 시작 시각이 {@code baselineStartedAt}보다 늦어졌는지로 확인합니다.
     *
     * @return 202 시작함 · 400 모르는 태스크 · 502 수집기에 닿지 못함
     */
    @PostMapping("/status/run/{taskName}")
    public ResponseEntity<Map<String, Object>> runTask(@PathVariable String taskName) {
        return ResponseEntity.accepted().body(status.startTask(taskName));
    }

    @PostMapping("/verification")
    public Map<String, Object> verification() {
        return verification.run();
    }

    // ------------------------------------------------- 📋 전체 원본 데이터
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

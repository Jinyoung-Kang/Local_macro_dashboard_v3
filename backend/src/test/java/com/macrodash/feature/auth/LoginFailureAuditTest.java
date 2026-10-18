package com.macrodash.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 로그인 실패 감사 표본(QA-125) — 무엇을 남길지만 봅니다(DB 없음, 시간은 테스트가 움직임). */
class LoginFailureAuditTest {

    private final LoginThrottleTest.MutableClock clock = new LoginThrottleTest.MutableClock();
    private final LoginFailureAudit audit = new LoginFailureAudit(clock);

    /** 있는 계정 실패를 {@code count}번, {@code gap} 간격으로 — 남긴 행의 실패 수 목록. */
    private List<Object> knownFailures(long userId, int count, Duration gap) {
        List<Object> written = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                clock.advance(gap);
            }
            Map<String, Object> detail = audit.knownFailure(userId);
            if (detail != null) {
                written.add(detail.get("failures"));
            }
        }
        return written;
    }

    @Test
    @DisplayName("있는 계정은 묶음의 첫 실패·무료 시도를 다 쓴 5번째·그 뒤 2의 거듭제곱 번째만 남긴다")
    void knownAccountKeepsFirstFreeLimitAndPowersOfTwo() {
        assertThat(knownFailures(42, 40, Duration.ofSeconds(1))).containsExactly(1, 5, 8, 16, 32);
    }

    @Test
    @DisplayName("마지막 실패에서 15분 조용하면 묶음이 끝나 다음 실패가 다시 첫 번째로 남는다")
    void quietPeriodStartsNewWindow() {
        knownFailures(42, 3, Duration.ofSeconds(1));
        clock.advance(LoginThrottle.FORGET_AFTER.plusSeconds(1));

        assertThat(audit.knownFailure(42)).containsEntry("failures", 1);
    }

    @Test
    @DisplayName("공격이 이어지는 동안에는 거듭제곱 사이에도 한 시간에 한 번은 남긴다")
    void ongoingAttackLeavesHourlyHeartbeat() {
        // 10분마다 한 번(15분 안이라 묶음이 이어짐), 10시간 — 61번 중 12번만 남고, 남긴 행 사이는 한 시간을 넘지 않는다.
        assertThat(knownFailures(42, 61, Duration.ofMinutes(10)))
                .containsExactly(1, 5, 8, 14, 16, 22, 28, 32, 38, 44, 50, 56);
    }

    @Test
    @DisplayName("성공하면 묶음을 닫고 그 실패 수를 돌려준다 — 다음 실패는 다시 첫 번째. 묶음이 없거나 끝났으면 0")
    void successReportsPriorFailuresAndClosesWindow() {
        knownFailures(42, 7, Duration.ofSeconds(1));

        assertThat(audit.closeWindow(42)).isEqualTo(7);
        assertThat(audit.closeWindow(42)).as("이미 닫음").isZero();
        assertThat(audit.knownFailure(42)).containsEntry("failures", 1);

        clock.advance(LoginThrottle.FORGET_AFTER.plusSeconds(1));
        assertThat(audit.closeWindow(42)).as("15분 지나 끝난 묶음").isZero();
        assertThat(audit.closeWindow(7)).as("실패가 없던 계정").isZero();
    }

    @Test
    @DisplayName("없는 계정은 한 시간에 30건까지만 이메일과 함께 남기고, 끝난 시간의 넘친 개수를 요약으로 한 번 꺼낸다")
    void unknownAccountsAreCappedPerHourWithSummary() {
        Instant start = clock.instant();
        int written = 0;
        for (int i = 0; i < 1000; i++) {
            Map<String, Object> detail = audit.unknownFailure("nobody" + i + "@test.local");
            if (detail != null) {
                assertThat(detail).containsEntry("email", "nobody" + i + "@test.local");
                written++;
            }
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(written).isEqualTo(LoginFailureAudit.UNKNOWN_PER_WINDOW);
        assertThat(audit.takeSuppressed(false)).as("시간이 끝나기 전에는 꺼내지 않음").isEmpty();

        clock.now = start.plus(LoginFailureAudit.WINDOW);
        LoginFailureAudit.Suppressed summary = audit.takeSuppressed(false).orElseThrow();
        assertThat(summary.detail()).containsEntry("count", 970).containsEntry("from", start.toString())
                .containsEntry("to", start.plus(LoginFailureAudit.WINDOW).toString())
                .containsEntry("limit", LoginFailureAudit.UNKNOWN_PER_WINDOW);
        assertThat(audit.takeSuppressed(false)).as("한 번만").isEmpty();
        assertThat(audit.unknownFailure(null)).as("새 시간은 다시 남김(형식이 아닌 이메일은 빈 detail)").isEmpty();
    }

    @Test
    @DisplayName("요약을 쓰지 못해 돌려놓으면 다음에 꺼낼 때 그사이 끝난 시간과 합쳐 한 줄이 된다")
    void unwrittenSummaryIsKeptAndMerged() {
        Instant first = clock.instant();
        for (int i = 0; i < LoginFailureAudit.UNKNOWN_PER_WINDOW + 3; i++) {
            audit.unknownFailure(null);
        }
        clock.advance(LoginFailureAudit.WINDOW);
        LoginFailureAudit.Suppressed failed = audit.takeSuppressed(false).orElseThrow();
        audit.putBack(failed);   // DB에 쓰지 못함

        Instant second = clock.instant();
        for (int i = 0; i < LoginFailureAudit.UNKNOWN_PER_WINDOW + 4; i++) {
            audit.unknownFailure(null);
        }
        clock.advance(LoginFailureAudit.WINDOW);

        assertThat(audit.takeSuppressed(false).orElseThrow())
                .isEqualTo(new LoginFailureAudit.Suppressed(7, first, second.plus(LoginFailureAudit.WINDOW)));
    }

    @Test
    @DisplayName("종료할 때는 아직 끝나지 않은 시간의 넘친 개수도 지금 시각까지로 꺼낸다")
    void shutdownTakesOpenWindow() {
        Instant start = clock.instant();
        for (int i = 0; i < LoginFailureAudit.UNKNOWN_PER_WINDOW + 2; i++) {
            audit.unknownFailure(null);
        }
        clock.advance(Duration.ofMinutes(5));

        assertThat(audit.takeSuppressed(true).orElseThrow())
                .isEqualTo(new LoginFailureAudit.Suppressed(2, start, start.plus(Duration.ofMinutes(5))));
        assertThat(audit.unknownFailure(null)).as("닫았으니 새 시간").isNotNull();
    }

    @Test
    @DisplayName("없는 계정으로 상한을 채워도 있는 계정의 실패는 그대로 남고, 계정 상태도 늘지 않는다")
    void unknownFloodDoesNotSilenceKnownAccounts() {
        for (int i = 0; i < 25_000; i++) {
            audit.unknownFailure("junk" + i + "@test.local");
        }

        assertThat(audit.trackedAccounts()).as("없는 계정은 계정 상태를 만들지 않음").isZero();
        assertThat(audit.knownFailure(42)).containsEntry("failures", 1);
    }

    @Test
    @DisplayName("계정 묶음이 상한에 닿으면 끝난 묶음을 정리한다")
    void endedStreaksArePrunedWhenLarge() {
        for (long id = 0; id < LoginFailureAudit.MAX_TRACKED; id++) {
            audit.knownFailure(id);
        }
        clock.advance(LoginThrottle.FORGET_AFTER.plusSeconds(1));

        audit.knownFailure(-1);

        assertThat(audit.trackedAccounts()).isEqualTo(1);
    }
}

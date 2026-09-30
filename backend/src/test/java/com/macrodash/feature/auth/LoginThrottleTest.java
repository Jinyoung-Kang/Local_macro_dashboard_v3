package com.macrodash.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {

    /** 테스트가 시간을 직접 움직이는 시계. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-25T00:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Test
    @DisplayName("무료 시도까지는 잠그지 않고, 그다음 실패부터 잠근다")
    void locksAfterFreeAttempts() {
        MutableClock clock = new MutableClock();
        LoginThrottle throttle = new LoginThrottle(clock);

        for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS; i++) {
            assertThat(throttle.recordFailure("a")).isZero();
            assertThat(throttle.retryAfter("a")).isZero();
        }
        assertThat(throttle.recordFailure("a")).isEqualTo(Duration.ofSeconds(30));
        assertThat(throttle.retryAfter("a")).isEqualTo(Duration.ofSeconds(30));
        assertThat(throttle.retryAfter("b")).as("다른 주소는 영향 없음").isZero();
    }

    @Test
    @DisplayName("잠금 시간은 두 배씩 늘고 15분에서 멈춘다")
    void backoffDoublesAndCaps() {
        assertThat(LoginThrottle.lockFor(6)).isEqualTo(Duration.ofSeconds(30));
        assertThat(LoginThrottle.lockFor(7)).isEqualTo(Duration.ofSeconds(60));
        assertThat(LoginThrottle.lockFor(8)).isEqualTo(Duration.ofSeconds(120));
        assertThat(LoginThrottle.lockFor(50)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("잠금이 끝나면 다시 시도할 수 있고, 성공하면 기록이 지워진다")
    void unlocksAfterWaitAndResetsOnSuccess() {
        MutableClock clock = new MutableClock();
        LoginThrottle throttle = new LoginThrottle(clock);
        for (int i = 0; i <= LoginThrottle.FREE_ATTEMPTS; i++) {
            throttle.recordFailure("a");
        }
        clock.advance(Duration.ofSeconds(31));
        assertThat(throttle.retryAfter("a")).isZero();

        throttle.recordSuccess("a");
        assertThat(throttle.recordFailure("a")).as("성공 후에는 처음부터 다시 셈").isZero();
    }
}

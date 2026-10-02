package com.macrodash.feature.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

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

    private final MutableClock clock = new MutableClock();
    private final List<Long> sleeps = new ArrayList<>();
    private final LoginThrottle throttle = new LoginThrottle(clock, sleeps::add);

    private LoginThrottle.Outcome wrong(String client) {
        return throttle.attempt(client, () -> false);
    }

    private LoginThrottle.Outcome right(String client) {
        return throttle.attempt(client, () -> true);
    }

    @Test
    @DisplayName("무료 시도까지는 바로 확인하고, 그다음부터는 느리게 확인한다")
    void slowsDownAfterFreeAttempts() {
        for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS; i++) {
            assertThat(wrong("a").retryAfter()).isZero();
        }
        assertThat(sleeps).as("무료 시도는 기다리지 않음").isEmpty();

        LoginThrottle.Outcome sixth = wrong("a");
        assertThat(sixth.accepted()).isFalse();
        assertThat(sixth.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(sleeps).containsExactly(LoginThrottle.SLOW_DELAY_MS);
        assertThat(throttle.isSlowed("b")).as("다른 주소는 영향 없음").isFalse();
    }

    @Test
    @DisplayName("실패가 쌓인 뒤에도 맞는 비밀번호는 통과한다 — 잠금이 아니라 지연이다")
    void correctPasswordAlwaysPasses() {
        for (int i = 0; i < 20; i++) {
            wrong("a");
        }
        LoginThrottle.Outcome outcome = right("a");

        assertThat(outcome.accepted()).isTrue();
        assertThat(throttle.isSlowed("a")).as("성공하면 기록이 지워짐").isFalse();
    }

    @Test
    @DisplayName("대기 힌트는 두 배씩 늘고 15분에서 멈춘다")
    void hintDoublesAndCaps() {
        assertThat(LoginThrottle.hintFor(6)).isEqualTo(Duration.ofSeconds(30));
        assertThat(LoginThrottle.hintFor(7)).isEqualTo(Duration.ofSeconds(60));
        assertThat(LoginThrottle.hintFor(8)).isEqualTo(Duration.ofSeconds(120));
        assertThat(LoginThrottle.hintFor(50)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("마지막 실패에서 15분이 지나면 실패 기록을 잊는다")
    void forgetsFailuresAfterQuietPeriod() {
        for (int i = 0; i <= LoginThrottle.FREE_ATTEMPTS; i++) {
            wrong("a");
        }
        assertThat(throttle.isSlowed("a")).isTrue();

        clock.advance(LoginThrottle.FORGET_AFTER.plusSeconds(1));
        assertThat(throttle.isSlowed("a")).isFalse();
        assertThat(wrong("a").retryAfter()).as("오타 한 번이 다시 긴 대기가 되지 않음").isZero();
    }

    @Test
    @DisplayName("느린 시도는 한 번에 하나만 받고, 겹치면 기다리지 않고 혼잡으로 답한다")
    void onlyOneSlowAttemptAtATime() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LoginThrottle blocking = new LoginThrottle(clock, millis -> {
            inside.countDown();
            release.await();
        });
        for (int i = 0; i < LoginThrottle.FREE_ATTEMPTS; i++) {
            blocking.attempt("attacker", () -> false);
        }

        AtomicReference<LoginThrottle.Outcome> first = new AtomicReference<>();
        Thread thread = new Thread(() -> first.set(blocking.attempt("attacker", () -> false)));
        thread.start();
        inside.await();

        LoginThrottle.Outcome second = blocking.attempt("attacker", () -> false);
        assertThat(second.busy()).isTrue();
        assertThat(second.retryAfter()).isEqualTo(LoginThrottle.BUSY_RETRY);

        release.countDown();
        thread.join();
        assertThat(first.get().accepted()).isFalse();
        assertThat(first.get().busy()).isFalse();
    }
}

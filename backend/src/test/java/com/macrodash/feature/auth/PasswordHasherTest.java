package com.macrodash.feature.auth;

import com.macrodash.support.TooManyRequestsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordHasherTest {

    /** 해시 한 번에 일정 시간이 걸리고, 동시에 몇 개가 돌았는지 세는 가짜 인코더(Argon2는 한 번에 16MB). */
    static final class SlowEncoder implements PasswordEncoder {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        private final long millis;

        SlowEncoder(long millis) {
            this.millis = millis;
        }

        private void work() {
            int now = running.incrementAndGet();
            maxRunning.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
        }

        @Override
        public String encode(CharSequence raw) {
            work();
            return "hash:" + raw;
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            work();
            return encoded.equals("hash:" + raw);
        }
    }

    @Test
    @DisplayName("QA-019 비밀번호 해시는 동시에 코어 수(최소 2)까지만 돈다 — 인증 없는 로그인으로 메모리를 고갈시키지 못하게")
    void concurrentHashingIsBounded() throws Exception {
        SlowEncoder encoder = new SlowEncoder(100);
        PasswordHasher hasher = new PasswordHasher(encoder);
        int bound = Math.max(2, Runtime.getRuntime().availableProcessors());
        int callers = Math.max(32, bound * 4);

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            String password = "pw" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    // 없는 계정도 가짜 해시로 한 번 비교합니다 — 공격자가 이메일을 바꿔 가며 부르는 경로
                    return hasher.matchesDummy(password);
                } catch (RuntimeException busy) {
                    return false; // 넘친 요청은 거절돼도 됨(429)
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();

        assertThat(encoder.maxRunning.get()).as("동시에 돈 해시 수").isLessThanOrEqualTo(bound);
    }

    @Test
    @DisplayName("QA-019 자리가 나지 않으면 기다리다 429로 거절한다 — 요청 스레드를 끝없이 붙잡지 않는다")
    void rejectsWhenNoSlotFreesUp() throws Exception {
        SlowEncoder encoder = new SlowEncoder(500);
        PasswordHasher hasher = new PasswordHasher(encoder, 1, Duration.ofMillis(50));
        Thread holder = new Thread(() -> hasher.matchesDummy("first"));
        holder.start();
        while (encoder.running.get() == 0) {
            Thread.onSpinWait();
        }

        assertThatThrownBy(() -> hasher.matches("second", "hash:second"))
                .isInstanceOf(TooManyRequestsException.class);
        holder.join();
        assertThat(hasher.matches("third", "hash:third")).as("자리가 나면 다시 받음").isTrue();
    }
}

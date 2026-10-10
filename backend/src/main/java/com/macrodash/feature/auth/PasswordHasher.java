package com.macrodash.feature.auth;

import com.macrodash.support.TooManyRequestsException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 비밀번호 해시 — Argon2id(OWASP 비밀번호 저장 권장 1순위).
 *
 * <p>설정은 Spring Security의 권장 기본값(메모리 16MB·반복 2·병렬 1·솔트 16바이트·해시 32바이트)입니다.
 * 로그인 한 번에 수십 ms가 걸리는 것이 목적입니다 — 유출된 해시를 대입해 보는 비용을 올립니다.
 *
 * <p><b>동시에 도는 해시 수에 상한이 있습니다</b>(코어 수, 최소 2). 해시 한 번이 16MB를 잡으므로, 상한이 없으면
 * 인증 없는 로그인 요청 수십 개를 한꺼번에 보내 힙을 고갈시킬 수 있었습니다(QA-019) — 실패 지연({@link LoginThrottle})은
 * (주소, 이메일)마다라 이메일을 바꿔 가며 보내면 피해 가고, 없는 이메일도 가짜 해시를 돌립니다. 자리가 날 때까지
 * {@value #WAIT_SECONDS}초 기다리고, 그래도 없으면 429입니다(정상 로그인은 해시 수십 ms라 거의 기다리지 않음).
 */
@Component
public class PasswordHasher {

    static final long WAIT_SECONDS = 3;

    private final PasswordEncoder encoder;
    private final Semaphore slots;
    private final Duration wait;

    /** 없는 계정으로 로그인할 때도 같은 시간이 걸리게 비교할 가짜 해시(계정 존재 여부를 응답 시간으로 드러내지 않음). */
    private final String dummyHash;

    public PasswordHasher() {
        this(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
    }

    /** 테스트가 느린 가짜 인코더로 바꿔 끼울 수 있게 합니다. */
    PasswordHasher(PasswordEncoder encoder) {
        this(encoder, defaultSlots(), Duration.ofSeconds(WAIT_SECONDS));
    }

    PasswordHasher(PasswordEncoder encoder, int slots, Duration wait) {
        this.encoder = encoder;
        this.slots = new Semaphore(slots, true);
        this.wait = wait;
        this.dummyHash = encoder.encode("dummy-password-for-constant-time");
    }

    static int defaultSlots() {
        return Math.max(2, Runtime.getRuntime().availableProcessors());
    }

    /** @throws TooManyRequestsException 해시 자리가 {@value #WAIT_SECONDS}초 안에 나지 않음 */
    public String hash(String password) {
        return guarded(() -> encoder.encode(password));
    }

    /** @throws TooManyRequestsException 해시 자리가 {@value #WAIT_SECONDS}초 안에 나지 않음 */
    public boolean matches(String password, String hash) {
        if (password == null || hash == null) {
            return false;
        }
        return guarded(() -> encoder.matches(password, hash));
    }

    /** 결과는 늘 false. 계정이 없을 때 시간을 맞추려고 부릅니다. */
    public boolean matchesDummy(String password) {
        guarded(() -> encoder.matches(password == null ? "" : password, dummyHash));
        return false;
    }

    private <T> T guarded(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new TooManyRequestsException("요청이 몰려 있습니다. 잠시 후 다시 시도하세요.", Duration.ofSeconds(1));
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }
}

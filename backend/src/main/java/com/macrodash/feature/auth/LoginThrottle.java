package com.macrodash.feature.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 비밀번호 대입(brute force)을 늦춥니다.
 *
 * <p>화면·API는 기본으로 같은 와이파이의 다른 기기에도 열려 있고(WEB_BIND_HOST=0.0.0.0),
 * 비밀번호는 한 개뿐입니다. 제한이 없으면 초당 수백 번 대입할 수 있습니다.
 *
 * <p>규칙 — 연속 실패가 {@value #FREE_ATTEMPTS}회를 넘으면 잠급니다. 잠금 시간은
 * 30초에서 시작해 실패할 때마다 두 배, 최대 15분입니다. 성공하면 기록을 지웁니다.
 *
 * <p>주의사항
 * <ul>
 *   <li>키는 접속 주소(remoteAddr)입니다. X-Forwarded-For는 클라이언트가 마음대로
 *       쓸 수 있어 믿지 않습니다. 도커 포트 포워딩 환경에서는 모든 접속이 같은
 *       게이트웨이 주소로 보일 수 있어, 그때는 사실상 <b>전체 공통</b> 제한이 됩니다.
 *       1인용 대시보드라 그쪽이 더 안전한 기본값입니다(최악의 경우 주인도 최대 15분 대기).</li>
 *   <li>상태는 메모리에만 둡니다. 재시작하면 초기화됩니다(단일 인스턴스 전제).</li>
 * </ul>
 */
@Component
public class LoginThrottle {

    static final int FREE_ATTEMPTS = 5;
    static final Duration BASE_LOCK = Duration.ofSeconds(30);
    static final Duration MAX_LOCK = Duration.ofMinutes(15);

    /** 기록이 이보다 많아지면 오래된 것을 정리합니다(주소를 바꿔 가며 메모리를 채우는 공격 대비). */
    private static final int MAX_TRACKED = 10_000;

    private record Record(int failures, Instant lockedUntil, Instant lastFailure) {
    }

    private final Map<String, Record> records = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /**
     * 지금 로그인 시도를 받아도 되는지 확인합니다.
     *
     * @param client 접속 주소
     * @return 잠겨 있으면 남은 시간, 아니면 {@link Duration#ZERO}
     */
    public Duration retryAfter(String client) {
        Record record = records.get(client);
        if (record == null || record.lockedUntil() == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(clock.instant(), record.lockedUntil());
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * 실패를 기록합니다.
     *
     * @param client 접속 주소
     * @return 이번 실패로 걸린 잠금 시간. 아직 무료 시도가 남았으면 {@link Duration#ZERO}
     */
    public Duration recordFailure(String client) {
        pruneIfLarge();
        Instant now = clock.instant();
        Record next = records.compute(client, (key, old) -> {
            int failures = (old == null ? 0 : old.failures()) + 1;
            Instant lockedUntil = failures > FREE_ATTEMPTS ? now.plus(lockFor(failures)) : null;
            return new Record(failures, lockedUntil, now);
        });
        return next.lockedUntil() == null ? Duration.ZERO : Duration.between(now, next.lockedUntil());
    }

    /** 로그인 성공 — 해당 주소의 기록을 지웁니다. */
    public void recordSuccess(String client) {
        records.remove(client);
    }

    /** 무료 시도를 넘긴 n번째 실패의 잠금 시간: 30초 × 2^(n-무료-1), 최대 15분. */
    static Duration lockFor(int failures) {
        int exponent = Math.min(failures - FREE_ATTEMPTS - 1, 10);
        Duration lock = BASE_LOCK.multipliedBy(1L << exponent);
        return lock.compareTo(MAX_LOCK) > 0 ? MAX_LOCK : lock;
    }

    private void pruneIfLarge() {
        if (records.size() < MAX_TRACKED) {
            return;
        }
        Instant cutoff = clock.instant().minus(MAX_LOCK);
        records.entrySet().removeIf(entry -> entry.getValue().lastFailure().isBefore(cutoff));
    }
}

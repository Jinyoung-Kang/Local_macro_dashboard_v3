package com.macrodash.feature.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

/**
 * 로그인 비밀번호 대입(brute force)을 늦춥니다.
 *
 * <p>화면·API는 기본으로 같은 와이파이의 다른 기기에도 열려 있고(WEB_BIND_HOST=0.0.0.0),
 * 비밀번호는 한 개뿐입니다. 제한이 없으면 초당 수백 번 대입할 수 있습니다.
 *
 * <p>규칙 — 한 주소의 연속 실패가 {@value #FREE_ATTEMPTS}회를 넘으면 그 주소의 시도는
 * <b>느리게</b> 처리합니다. 느린 시도는 한 번에 하나만(전역) 받고, 받은 시도는
 * {@value #SLOW_DELAY_MS}ms 뒤에 비밀번호를 확인합니다. 다른 느린 시도가 진행 중이면
 * 즉시 429입니다. 그래서 공격자는 전체적으로 초당 한 번 정도만 대입할 수 있고(하루 8만여
 * 번 — {@code make setup}이 만드는 16자 무작위 비밀번호에는 의미 없는 횟수), 요청 스레드를
 * 잠그고 기다리는 시도도 하나뿐입니다.
 *
 * <p><b>맞는 비밀번호는 잠기지 않습니다.</b> 다만 느린 차선이 차 있는 바로 그 순간에는
 * 주인도 429({@code Retry-After} 1초)를 받을 수 있고, 다시 시도하면 통과합니다 — 공격자가
 * 초당 수십 번 두드리는 동안의 남은 위험입니다. 예전에는 실패가 쌓이면 주소를 잠그고
 * 비밀번호를 보지도 않았습니다. Docker Desktop은 모든 접속을 같은 게이트웨이 주소로
 * 보여 주므로 잠금이 사실상 전역이었고, 같은 와이파이의 누구든 15분마다 틀린 비밀번호
 * 한 번으로 주인의 로그인을 영구히 막을 수 있었습니다. 실패 횟수도 성공 전엔 줄지 않아
 * 한 번 잠긴 뒤에는 오타 한 번에 15분이었습니다. 지금은 마지막 실패에서 {@value}분이
 * 지나면 기록이 사라집니다.
 *
 * <p>주의사항
 * <ul>
 *   <li>키는 접속 주소(remoteAddr)입니다. X-Forwarded-For는 클라이언트가 마음대로
 *       쓸 수 있어 믿지 않습니다.</li>
 *   <li>상태는 메모리에만 둡니다. 재시작하면 초기화됩니다(단일 인스턴스 전제).</li>
 * </ul>
 */
@Component
public class LoginThrottle {

    static final int FREE_ATTEMPTS = 5;
    static final long SLOW_DELAY_MS = 1000;
    /** 마지막 실패에서 이만큼 지나면 그 주소의 실패 기록을 잊습니다. */
    static final Duration FORGET_AFTER = Duration.ofMinutes(15);
    /** 느린 시도가 겹쳤을 때 돌려주는 Retry-After. */
    static final Duration BUSY_RETRY = Duration.ofSeconds(1);

    /** 기록이 이보다 많아지면 오래된 것을 정리합니다(주소를 바꿔 가며 메모리를 채우는 공격 대비). */
    private static final int MAX_TRACKED = 10_000;

    /** 시도 결과. {@code busy}면 비밀번호를 보지 않았습니다. */
    public record Outcome(boolean accepted, boolean busy, Duration retryAfter) {
        static final Outcome ACCEPTED = new Outcome(true, false, Duration.ZERO);
        static final Outcome BUSY = new Outcome(false, true, BUSY_RETRY);

        static Outcome rejected(Duration retryAfter) {
            return new Outcome(false, false, retryAfter);
        }
    }

    private record Record(int failures, Instant lastFailure) {
    }

    private final Map<String, Record> records = new ConcurrentHashMap<>();
    private final Semaphore slowLane = new Semaphore(1);
    private final Clock clock;
    private final Sleeper sleeper;

    /** 테스트가 실제로 기다리지 않도록 대기를 바꿔 끼울 수 있게 합니다. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public LoginThrottle() {
        this(Clock.systemUTC(), Thread::sleep);
    }

    LoginThrottle(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * 로그인 시도를 처리합니다. 비밀번호 확인은 {@code passwordMatches}가 합니다.
     *
     * @param client          접속 주소
     * @param passwordMatches 비밀번호가 맞으면 true
     * @return 통과·거절(Retry-After 힌트 포함)·혼잡
     */
    public Outcome attempt(String client, BooleanSupplier passwordMatches) {
        boolean slow = isSlowed(client);
        if (slow && !slowLane.tryAcquire()) {
            return Outcome.BUSY;
        }
        try {
            if (slow) {
                sleeper.sleep(SLOW_DELAY_MS);
            }
            if (passwordMatches.getAsBoolean()) {
                records.remove(client);
                return Outcome.ACCEPTED;
            }
            return Outcome.rejected(recordFailure(client));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.BUSY;
        } finally {
            if (slow) {
                slowLane.release();
            }
        }
    }

    /** 이 주소의 다음 시도가 느린 차선으로 가는가(무료 시도를 넘겼고 아직 잊히지 않음). */
    boolean isSlowed(String client) {
        Record record = records.get(client);
        if (record == null) {
            return false;
        }
        if (record.lastFailure().plus(FORGET_AFTER).isBefore(clock.instant())) {
            records.remove(client, record);
            return false;
        }
        return record.failures() >= FREE_ATTEMPTS;
    }

    /**
     * 실패를 기록합니다.
     *
     * @return 화면에 보여 줄 대기 힌트. 무료 시도가 남았으면 {@link Duration#ZERO}
     */
    Duration recordFailure(String client) {
        pruneIfLarge();
        Instant now = clock.instant();
        Record next = records.compute(client, (key, old) -> {
            boolean forgotten = old == null || old.lastFailure().plus(FORGET_AFTER).isBefore(now);
            int failures = (forgotten ? 0 : old.failures()) + 1;
            return new Record(failures, now);
        });
        return next.failures() > FREE_ATTEMPTS ? hintFor(next.failures()) : Duration.ZERO;
    }

    /** 무료 시도를 넘긴 n번째 실패의 대기 힌트: 30초 × 2^(n-무료-1), 최대 15분. 강제가 아니라 안내입니다. */
    static Duration hintFor(int failures) {
        int exponent = Math.min(failures - FREE_ATTEMPTS - 1, 10);
        Duration hint = Duration.ofSeconds(30).multipliedBy(1L << exponent);
        return hint.compareTo(FORGET_AFTER) > 0 ? FORGET_AFTER : hint;
    }

    private void pruneIfLarge() {
        if (records.size() < MAX_TRACKED) {
            return;
        }
        Instant cutoff = clock.instant().minus(FORGET_AFTER);
        records.entrySet().removeIf(entry -> entry.getValue().lastFailure().isBefore(cutoff));
    }
}

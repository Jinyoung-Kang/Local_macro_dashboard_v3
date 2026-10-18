package com.macrodash.feature.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그인 실패를 감사 기록에 남길지 정하는 표본 규칙(QA-125).
 *
 * <p>예전에는 비밀번호를 본 실패마다 한 행을 남겼습니다. 로그인 없이 부를 수 있는 경로이고 기록은 1년 보존이라,
 * 이메일을 바꿔 가며 보내면(지연 키 (주소, 이메일)가 매번 새로 생겨 모두 무료 시도) 하루 수백만 행까지 쌓을 수 있었습니다.
 * 그래서 표본의 단위를 공격자가 새로 만들 수 없는 것으로 둡니다.
 *
 * <ul>
 *   <li><b>메일 인증을 마친 계정</b>은 사용자 번호마다 연속 실패를 한 묶음으로 셉니다(주소를 바꿔도 같은 묶음). 마지막 실패에서
 *       {@link #STREAK_QUIET}(하루)가 지나면 묶음이 끝납니다. 묶음의 첫 실패, 무료 시도를 다 쓴 5번째
 *       ({@link LoginThrottle#FREE_ATTEMPTS}), 그 뒤 2의 거듭제곱 번째(8·16·32…)와, 공격이 이어지는 동안 한 시간에 한 번을
 *       그때까지의 실패 수와 함께 남깁니다. 로그인에 성공하면 묶음을 닫고 그 실패 수를 성공 기록에 붙입니다
 *       ({@link #closeWindow}) — 표본 사이의 실패가 행으로 남지 않아도 대입이 성공했는지는 보입니다.</li>
 *   <li><b>없는 계정</b>(형식이 아닌 이메일 포함)과 <b>메일 인증 전 계정</b>(가입으로 누구나 만듦)은 전체를 한 묶음으로,
 *       한 시간에 {@value #UNKNOWN_PER_WINDOW}건까지만 남기고 넘친 것은 개수만 셉니다. 끝난 시간의 넘친 개수는 정기 작업이
 *       요약 한 줄로 남깁니다({@link #takeSuppressed}). 인증을 마친 계정의 몫과 따로라, 쓰레기 요청으로 상한을 채워도 노리는
 *       계정의 실패는 지워지지 않습니다.</li>
 * </ul>
 *
 * <p>접속 주소는 받지도 두지도 않습니다(명세 B10). 상태는 메모리에만 있어 재시작하면 새로 셉니다 — 행이 줄지는 않고
 * (묶음의 첫 실패가 다시 남음) 재시작 뒤 성공 기록의 직전 실패 수가 작게 나올 뿐입니다. DB를 모릅니다 — 무엇을 남길지만
 * 정하고, 쓰는 것은 {@link AuthService}입니다.
 */
final class LoginFailureAudit {

    /** 없는 계정 실패를 이메일과 함께 남기는 한 시간 상한. 넘친 것은 개수 요약 한 줄. */
    static final int UNKNOWN_PER_WINDOW = 30;
    /** 없는 계정 상한의 단위이자, 있는 계정 공격이 이어지는 동안 적어도 한 번 남기는 간격. */
    static final Duration WINDOW = Duration.ofHours(1);
    /**
     * 계정 묶음이 끝나는 조용한 시간. 지연 기록의 {@link LoginThrottle#FORGET_AFTER}(15분)와 따로 둡니다 — 지연은 주인이 오타 뒤
     * 오래 기다리지 않게 빨리 잊어야 하지만, 감사 묶음까지 15분에 끝나면 4번 실패하고 15분 넘게 쉬기를 되풀이하는 대입(지연에
     * 한 번도 걸리지 않음)이 쉴 때마다 새 묶음이 되어 묶음마다 첫 실패만 남았습니다(하루 387번 → 'failures: 1' 97행, 성공 때 직전
     * 실패 수 3). 하루면 그렇게 끊어 하는 대입도 한 묶음이라 표본이 실제 누적 수를 남기고, 묶음을 끊으려면 하루를 쉬어야 합니다.
     * 키가 인증을 마친 실제 계정뿐이라 하루를 기억해도 메모리는 계정 수만큼입니다.
     */
    static final Duration STREAK_QUIET = Duration.ofHours(24);
    /** 기억하는 계정 묶음의 상한 — 키가 메일 인증을 마친 실제 계정뿐이라 닿기 어렵지만, 넘으면 끝난 묶음을 정리합니다. */
    static final int MAX_TRACKED = LoginThrottle.MAX_TRACKED;
    /** 정리는 이 간격에 한 번만(LoginThrottle과 같은 이유 — 차 있는 동안 실패마다 전체를 훑지 않게). */
    private static final Duration PRUNE_EVERY = Duration.ofSeconds(10);

    /** 계정 하나의 연속 실패 묶음. {@code write}는 이번 실패를 기록에 남길 차례인가. */
    private record Streak(int failures, Instant lastFailure, Instant lastWritten, boolean write) {
    }

    /** 상한을 넘겨 개수만 센 없는 계정 실패(아직 감사 기록에 쓰지 않은 것). */
    record Suppressed(int count, Instant from, Instant to) {

        /** 감사 기록 detail — 개수, 그 시간 범위, 그때의 상한. */
        Map<String, Object> detail() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("count", count);
            detail.put("from", from.toString());
            detail.put("to", to.toString());
            detail.put("limit", UNKNOWN_PER_WINDOW);
            return detail;
        }

        Suppressed merge(Suppressed other) {
            return new Suppressed(count + other.count, from.isBefore(other.from) ? from : other.from,
                    to.isAfter(other.to) ? to : other.to);
        }
    }

    private final Map<Long, Streak> streaks = new ConcurrentHashMap<>();
    private volatile Instant lastPrune = Instant.EPOCH;
    private final Clock clock;

    // 없는 계정 — this로 보호합니다.
    /** 지금 시간 묶음의 시작. 열린 묶음이 없으면 null. */
    private Instant unknownSince;
    private int unknownWritten;
    private int unknownSuppressed;
    /** 끝난 시간들의 넘친 개수(정기 작업이 쓰기 전). 쓰지 못한 것도 여기로 돌아와 합쳐집니다. */
    private Suppressed pending;

    LoginFailureAudit(Clock clock) {
        this.clock = clock;
    }

    /**
     * 메일 인증을 마친 계정의 실패 하나.
     *
     * @return 남길 차례면 감사 기록 detail({@code failures} = 이 묶음의 실패 수), 아니면 null
     */
    Map<String, Object> knownFailure(long userId) {
        pruneIfLarge();
        Instant now = clock.instant();
        Streak next = streaks.compute(userId, (id, old) -> {
            if (old == null || ended(old, now)) {
                return new Streak(1, now, now, true);
            }
            int failures = old.failures() + 1;
            boolean write = failures == LoginThrottle.FREE_ATTEMPTS
                    || (failures > LoginThrottle.FREE_ATTEMPTS && Integer.bitCount(failures) == 1)
                    || !now.isBefore(old.lastWritten().plus(WINDOW));
            return new Streak(failures, now, write ? now : old.lastWritten(), write);
        });
        if (!next.write()) {
            return null;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("failures", next.failures());
        return detail;
    }

    /**
     * 로그인 성공 — 이 계정의 묶음을 닫습니다.
     *
     * @return 닫은 묶음의 실패 수(묶음이 시작된 뒤 전부). 묶음이 없거나 이미 끝났으면 0
     */
    int closeWindow(long userId) {
        Streak streak = streaks.remove(userId);
        return streak == null || ended(streak, clock.instant()) ? 0 : streak.failures();
    }

    /**
     * 없는 계정(또는 메일 인증 전 계정)의 실패 하나 — 전체가 한 시간 몫을 나눠 씁니다.
     *
     * @param normalizedEmail 남길 이메일(없는 계정의 다듬은 이메일). 형식이 아니었거나 남기지 않을 때는 null
     * @return 이번 시간의 상한 안이면 감사 기록 detail(이메일이 있으면 {@code email}), 넘쳤으면 null(개수만 셈)
     */
    synchronized Map<String, Object> unknownFailure(String normalizedEmail) {
        Instant now = clock.instant();
        closeUnknownIfEnded(now);
        if (unknownSince == null) {
            unknownSince = now;
        }
        if (unknownWritten >= UNKNOWN_PER_WINDOW) {
            unknownSuppressed++;
            return null;
        }
        unknownWritten++;
        Map<String, Object> detail = new LinkedHashMap<>();
        if (normalizedEmail != null) {
            detail.put("email", normalizedEmail);
        }
        return detail;
    }

    /**
     * 끝난 시간의 넘친 개수를 꺼냅니다(꺼내면 비워짐). 쓰지 못했으면 {@link #putBack}으로 돌려놓습니다.
     *
     * @param includeOpen 아직 끝나지 않은 시간도 지금 닫을지(종료할 때 — 메모리의 개수를 잃지 않게)
     */
    synchronized Optional<Suppressed> takeSuppressed(boolean includeOpen) {
        Instant now = clock.instant();
        if (includeOpen) {
            closeUnknown(now);
        } else {
            closeUnknownIfEnded(now);
        }
        Suppressed taken = pending;
        pending = null;
        return Optional.ofNullable(taken);
    }

    /** 쓰지 못한 요약을 돌려놓습니다 — 그사이 끝난 시간이 있으면 합쳐 다음에 한 줄로 씁니다. */
    synchronized void putBack(Suppressed summary) {
        pending = pending == null ? summary : pending.merge(summary);
    }

    /** 지금 기억하는 계정 묶음 수(테스트용). */
    int trackedAccounts() {
        return streaks.size();
    }

    private static boolean ended(Streak streak, Instant now) {
        return streak.lastFailure().plus(STREAK_QUIET).isBefore(now);
    }

    private void closeUnknownIfEnded(Instant now) {
        if (unknownSince != null && !now.isBefore(unknownSince.plus(WINDOW))) {
            closeUnknown(unknownSince.plus(WINDOW));
        }
    }

    private void closeUnknown(Instant end) {
        if (unknownSince == null) {
            return;
        }
        if (unknownSuppressed > 0) {
            Suppressed ended = new Suppressed(unknownSuppressed, unknownSince, end);
            pending = pending == null ? ended : pending.merge(ended);
        }
        unknownSince = null;
        unknownWritten = 0;
        unknownSuppressed = 0;
    }

    private void pruneIfLarge() {
        if (streaks.size() < MAX_TRACKED) {
            return;
        }
        Instant now = clock.instant();
        if (lastPrune.plus(PRUNE_EVERY).isAfter(now)) {
            return;
        }
        lastPrune = now;
        streaks.values().removeIf(streak -> ended(streak, now));
    }
}

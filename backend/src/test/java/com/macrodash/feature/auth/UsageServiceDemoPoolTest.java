package com.macrodash.feature.auth;

import com.macrodash.store.UsageStore;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 데모 열람의 공용 하루 AI 리포트 한도(ADR 0014) — 메모리에서 세고 DB({@code usage_counters}, users FK)에는 쓰지 않습니다.
 * 계정 사용자의 한도는 {@link UsageAndAdminTest}(실제 DB)가 봅니다.
 */
class UsageServiceDemoPoolTest {

    /** 테스트가 시간을 직접 움직이는 시계. */
    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private static final CurrentUser DEMO = DemoViewer.user();

    private final UsageStore store = mock(UsageStore.class);

    private UsageService service(int demoLimit, Clock clock) {
        return new UsageService(store, 20, demoLimit, clock);
    }

    @Test
    @DisplayName("방문자 전체가 하루 한도를 함께 쓰고, 넘으면 세지 않고 막는다 — DB에는 아무것도 쓰지 않는다")
    void sharedLimitWithoutDatabase() {
        UsageService usage = service(3, Clock.systemUTC());

        for (int i = 1; i <= 3; i++) {
            UsageService.Quota quota = usage.consume(DEMO, UsageService.AI_REPORT);
            assertThat(quota.allowed()).isTrue();
            assertThat(quota.used()).isEqualTo(i);
            assertThat(quota.limit()).isEqualTo(3);
            assertThat(quota.shared()).isTrue();
        }
        UsageService.Quota exhausted = usage.consume(DEMO, UsageService.AI_REPORT);
        assertThat(exhausted.allowed()).isFalse();
        assertThat(exhausted.used()).isEqualTo(3);
        assertThat(exhausted.limit()).isEqualTo(3);
        assertThat(usage.demoQuota().used()).isEqualTo(3);
        assertThat(usage.demoQuota().allowed()).as("남은 건이 없음").isFalse();

        assertThat(usage.consume(DEMO, "other.kind").allowed()).as("공용 한도는 AI 리포트에만").isFalse();
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("실패한 호출은 되돌려 다시 쓰고, 0 밑으로는 내려가지 않는다")
    void refund() {
        UsageService usage = service(1, Clock.systemUTC());

        assertThat(usage.consume(DEMO, UsageService.AI_REPORT).allowed()).isTrue();
        assertThat(usage.consume(DEMO, UsageService.AI_REPORT).allowed()).isFalse();
        usage.refund(DEMO, UsageService.AI_REPORT);
        assertThat(usage.demoQuota().used()).isZero();
        assertThat(usage.consume(DEMO, UsageService.AI_REPORT).allowed()).as("되돌린 한 건을 다시 씀").isTrue();

        usage.refund(DEMO, UsageService.AI_REPORT);
        usage.refund(DEMO, UsageService.AI_REPORT);
        assertThat(usage.demoQuota().used()).as("음수가 되지 않음").isZero();
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("한국 날짜가 바뀌면(UTC 15:00 = KST 0시) 새로 센다 — 서버 시간대(UTC) 날짜가 아니다")
    void resetsAtKstMidnight() {
        // 2026-10-11 23:59 KST == 14:59 UTC. UTC 날짜는 그대로 11일이지만 1분 뒤 한국은 12일입니다.
        MutableClock clock = new MutableClock(Instant.parse("2026-10-11T14:59:00Z"));
        UsageService usage = service(2, clock);
        usage.consume(DEMO, UsageService.AI_REPORT);
        usage.consume(DEMO, UsageService.AI_REPORT);
        assertThat(usage.consume(DEMO, UsageService.AI_REPORT).allowed()).isFalse();

        clock.advance(Duration.ofSeconds(59));
        assertThat(usage.consume(DEMO, UsageService.AI_REPORT).allowed()).as("아직 같은 한국 날짜").isFalse();

        clock.advance(Duration.ofSeconds(1));
        UsageService.Quota nextDay = usage.consume(DEMO, UsageService.AI_REPORT);
        assertThat(nextDay.allowed()).isTrue();
        assertThat(nextDay.used()).isEqualTo(1);
        assertThat(usage.demoQuota().used()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시에 눌러도 한도를 넘지 않는다")
    void concurrentConsumersNeverExceedLimit() throws Exception {
        UsageService usage = service(50, Clock.systemUTC());
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            Callable<Boolean> call = () -> usage.consume(DEMO, UsageService.AI_REPORT).allowed();
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                calls.add(call);
            }
            long allowed = 0;
            for (Future<Boolean> result : pool.invokeAll(calls)) {
                allowed += result.get() ? 1 : 0;
            }
            assertThat(allowed).isEqualTo(50);
            assertThat(usage.demoQuota().used()).isEqualTo(50);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("계정 사용자는 지금처럼 DB 한도를 쓴다 — 데모 공용 한도와 섞이지 않는다")
    void accountUsersStillUseDatabase() {
        when(store.tryIncrement(anyLong(), any(), anyString(), anyInt())).thenReturn(true);
        when(store.count(anyLong(), any(), anyString())).thenReturn(1);
        UsageService usage = service(1, Clock.systemUTC());
        CurrentUser pro = new CurrentUser(7, "pro@test.local", "MEMBER", "PRO", null);

        UsageService.Quota quota = usage.consume(pro, UsageService.AI_REPORT);

        assertThat(quota.allowed()).isTrue();
        assertThat(quota.shared()).isFalse();
        assertThat(quota.limit()).isEqualTo(20);
        verify(store).tryIncrement(org.mockito.ArgumentMatchers.eq(7L), any(), org.mockito.ArgumentMatchers.eq(UsageService.AI_REPORT),
                org.mockito.ArgumentMatchers.eq(20));
        assertThat(usage.demoQuota().used()).as("데모 공용 한도는 그대로").isZero();
    }
}

package com.macrodash.feature.auth;

import com.macrodash.store.AuditLogStore;
import com.macrodash.store.EmailTokenStore;
import com.macrodash.store.SessionStore;
import com.macrodash.store.UserStore;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 가입·재설정 메일 요청 제한(RateLimit) — 기억하는 키가 끝없이 늘지 않는가(QA-073).
 *
 * <p>LoginThrottle에서 고친 '기록 맵 상한'(QA-019·055)이 이 제한에는 없었습니다. 이메일만 바꿔 보내면 키가 요청마다
 * 하나씩 늘고, 1만 개를 넘은 뒤로는 요청마다 맵 전체를 두 번 훑었습니다. DB 없이 돕니다(저장소는 목).
 */
class RateLimitTest {

    /** RateLimit이 기억하는 키 상한(RateLimit.MAX_TRACKED와 같은 값). */
    private static final int CAP = 10_000;

    private final LoginThrottleTest.MutableClock clock = new LoginThrottleTest.MutableClock();

    /** 지금 기억하는 키 수. */
    private static int tracked(RateLimit limit) {
        return ((Map<?, ?>) ReflectionTestUtils.getField(limit, "hits")).size();
    }

    @Test
    @Disabled("QA-073: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-073: 한 주소가 이메일만 바꿔 보내도 요청 기록이 늘지 않는다 — 주소 한도를 먼저 본다")
    void manyEmailsFromOneAddressStayBounded() {
        AccountMail mail = mock(AccountMail.class);
        when(mail.configured()).thenReturn(true);
        AccountService accounts = new AccountService(mock(UserStore.class), mock(SessionStore.class),
                mock(EmailTokenStore.class), mock(AuditLogStore.class), mock(PasswordHasher.class), mail, clock);

        int sent = 0;
        for (int i = 0; i < 20_000; i++) {
            if (accounts.requestReset("u" + i + "@example.com", "172.17.0.1").status() == 200) {
                sent++;
            }
        }

        assertThat(sent).as("주소마다 시간당 5번").isEqualTo(5);
        RateLimit limit = (RateLimit) ReflectionTestUtils.getField(accounts, "mailRequests");
        assertThat(tracked(limit)).as("주소 키 1개 + 허용된 이메일 키 5개만").isLessThanOrEqualTo(6);
    }

    @Test
    @Disabled("QA-073: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-073: 기록이 꽉 차면 처음 보는 키는 기록하지 않고 거절한다 — 이미 아는 키는 그대로 센다")
    void fullTableRefusesUnseenKeys() {
        RateLimit limit = new RateLimit(5, Duration.ofHours(1), clock);
        for (int i = 0; i < CAP; i++) {
            assertThat(limit.allow("k" + i)).isTrue();
        }

        assertThat(limit.allow("new")).as("꽉 찬 동안 처음 보는 키").isFalse();
        assertThat(tracked(limit)).isEqualTo(CAP);
        assertThat(limit.allow("k0")).as("아는 키는 한도 안에서 계속 허용").isTrue();
    }

    @Test
    @Disabled("QA-073: 수정 전 재현 — fix 커밋에서 켬")
    @DisplayName("QA-073: 꽉 찬 기록의 정리는 요청마다가 아니라 일정 간격에 한 번 — 창이 지난 키는 그때 빠진다")
    void fullTablePrunesOnInterval() {
        // 창(5초)을 정리 간격(10초)보다 짧게 잡아, '창은 지났지만 정리 간격은 안 지난' 순간을 만듭니다.
        RateLimit limit = new RateLimit(5, Duration.ofSeconds(5), clock);
        for (int i = 0; i < CAP; i++) {
            limit.allow("k" + i);
        }
        assertThat(limit.allow("new1")).as("꽉 참 — 정리해도 지울 것이 없음").isFalse();

        clock.advance(Duration.ofSeconds(6));
        assertThat(limit.allow("new2")).as("창은 지났지만 직전 정리에서 10초가 안 됨 — 다시 훑지 않음").isFalse();
        assertThat(tracked(limit)).isEqualTo(CAP);

        clock.advance(Duration.ofSeconds(5));
        assertThat(limit.allow("new3")).as("정리 간격이 지나 창 밖의 키를 지운 뒤 받음").isTrue();
        assertThat(tracked(limit)).isEqualTo(1);
    }
}

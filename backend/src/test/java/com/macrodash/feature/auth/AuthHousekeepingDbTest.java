package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import com.macrodash.store.EmailTokenStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 인증 테이블 정리 — 오래 만료·해지된 세션과 메일 토큰은 정기 작업이 지운다(실제 테스트 DB). */
class AuthHousekeepingDbTest {

    private final AuthTestDatabase db = new AuthTestDatabase("housekeep");

    @AfterEach
    void cleanUp() {
        db.cleanUp();
    }

    /** 이 서비스의 정기 작업(@Scheduled)을 모두 한 번씩 돌립니다 — 스케줄러가 부르는 것과 같습니다. */
    private static void runScheduled(Object service) throws Exception {
        for (Method method : service.getClass().getMethods()) {
            if (method.isAnnotationPresent(Scheduled.class)) {
                method.invoke(service);
            }
        }
    }

    @Test
    @DisplayName("QA-027 정기 작업이 오래 만료된 세션·메일 토큰을 지우고, 살아 있는 세션은 둔다")
    void scheduledJobsPurgeExpiredSessionsAndTokens() throws Exception {
        long userId = db.user("member", "member-password-1", "MEMBER", "FREE");
        UUID expired = UUID.randomUUID();
        UUID alive = UUID.randomUUID();
        db.jdbc.update("INSERT INTO user_sessions (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, now() - interval '40 days')",
                expired, userId, new byte[]{1, 2, 3, (byte) userId});
        db.jdbc.update("INSERT INTO user_sessions (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, now() + interval '1 day')",
                alive, userId, new byte[]{4, 5, 6, (byte) userId});
        db.jdbc.update("INSERT INTO email_tokens (token_hash, user_id, purpose, expires_at) VALUES (?, ?, 'VERIFY', now() - interval '10 days')",
                new byte[]{7, 8, 9, (byte) userId}, userId);

        AuthService auth = db.service(new AppProperties());
        AccountService accounts = new AccountService(db.users, db.sessions, new EmailTokenStore(db.jdbc), db.audit,
                db.hasher, mock(AccountMail.class));
        runScheduled(auth);
        runScheduled(accounts);

        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE id = ?", Integer.class, expired))
                .as("40일 전에 만료된 세션").isZero();
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE id = ?", Integer.class, alive))
                .as("살아 있는 세션").isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM email_tokens WHERE user_id = ?", Integer.class, userId))
                .as("10일 전에 만료된 메일 토큰").isZero();
    }

    @Test
    @DisplayName("감사 기록은 1년 보존 — 정기 작업이 1년 지난 기록만 지운다(사용자 결정 2026-10-11)")
    void scheduledJobPurgesAuditLogOlderThanOneYear() throws Exception {
        long userId = db.user("member", "member-password-1", "MEMBER", "FREE");
        db.jdbc.update("INSERT INTO audit_log (user_id, action, at) VALUES (?, 'test.old', now() - interval '400 days')", userId);
        db.jdbc.update("INSERT INTO audit_log (user_id, action, at) VALUES (?, 'test.recent', now() - interval '300 days')", userId);

        runScheduled(db.service(new AppProperties()));

        assertThat(db.jdbc.queryForList("SELECT action FROM audit_log WHERE user_id = ? AND action LIKE 'test.%'",
                String.class, userId)).containsExactly("test.recent");
    }
}

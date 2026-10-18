package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import com.macrodash.store.AuditLogStore;
import com.macrodash.store.SessionStore;
import com.macrodash.store.UserStore;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 계정 로그인과 DB 세션(ADR 0010).
 */
class AuthServiceTest {

    private final AuthTestDatabase db = new AuthTestDatabase("authsvc");
    private final AppProperties properties = new AppProperties();

    @AfterEach
    void cleanUp() {
        db.cleanUp();
    }

    @Test
    @DisplayName("맞는 이메일·비밀번호면 세션이 열리고, 쿠키 토큰으로 사용자를 찾는다. DB에는 토큰 원문이 없다")
    void loginOpensSession() {
        db.user("owner", "correct horse battery", "OWNER", "PRO");
        AuthService auth = db.service(properties);

        UserStore.User user = auth.verify("  AUTHSVC-owner@TEST.local ", "correct horse battery").orElseThrow();
        AuthService.Issued issued = auth.openSession(user, "JUnit");

        CurrentUser current = auth.authenticate(issued.token()).orElseThrow();
        assertThat(current.email()).isEqualTo(db.email("owner"));
        assertThat(current.isOwner()).isTrue();
        Integer plain = db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_sessions WHERE token_hash = convert_to(?, 'UTF8')", Integer.class,
                issued.token());
        assertThat(plain).as("토큰 원문은 저장하지 않음").isZero();
        Integer hashed = db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_sessions WHERE token_hash = sha256(convert_to(?, 'UTF8'))", Integer.class,
                issued.token());
        assertThat(hashed).as("SHA-256만 저장").isEqualTo(1);
    }

    @Test
    @DisplayName("틀린 비밀번호·없는 이메일·형식이 아닌 이메일은 모두 실패")
    void wrongCredentialsFail() {
        db.user("member", "correct horse battery", "MEMBER", "FREE");
        AuthService auth = db.service(properties);

        assertThat(auth.verify(db.email("member"), "wrong password!!")).isEmpty();
        assertThat(auth.verify(db.email("nobody"), "correct horse battery")).isEmpty();
        assertThat(auth.verify("not-an-email", "correct horse battery")).isEmpty();
        assertThat(auth.verify(null, null)).isEmpty();
        assertThat(auth.authenticate("forged-token")).isEmpty();
        assertThat(auth.authenticate(null)).isEmpty();
    }

    @Test
    @DisplayName("로그아웃한 세션은 백엔드를 다시 만들어도(재시작) 쓸 수 없다 — 예전 메모리 목록의 한계")
    void logoutSurvivesRestart() {
        long id = db.user("restart", "correct horse battery", "MEMBER", "FREE");
        AuthService before = db.service(properties);
        AuthService.Issued kept = before.openSession(db.users.findById(id).orElseThrow(), null);
        AuthService.Issued gone = before.openSession(db.users.findById(id).orElseThrow(), null);

        before.logout(gone.token());
        AuthService after = db.service(properties);   // 새 인스턴스 = 재시작

        assertThat(after.authenticate(gone.token())).isEmpty();
        assertThat(after.authenticate(kept.token())).isPresent();
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 다른 기기 세션은 끊기고 지금 세션은 남는다. 규칙에 어긋나면 거절")
    void changePasswordRevokesOtherSessions() {
        long id = db.user("change", "correct horse battery", "MEMBER", "FREE");
        AuthService auth = db.service(properties);
        UserStore.User user = db.users.findById(id).orElseThrow();
        AuthService.Issued phone = auth.openSession(user, "phone");
        AuthService.Issued laptop = auth.openSession(user, "laptop");
        CurrentUser me = auth.authenticate(laptop.token()).orElseThrow();

        assertThat(auth.changePassword(me, "wrong current!!", "new long passphrase")).contains("지금 비밀번호");
        assertThat(auth.changePassword(me, "correct horse battery", "short")).contains("10자");
        assertThat(auth.changePassword(me, "correct horse battery", "new long passphrase")).isNull();

        assertThat(auth.authenticate(phone.token())).as("다른 기기").isEmpty();
        assertThat(auth.authenticate(laptop.token())).as("지금 기기").isPresent();
        assertThat(auth.verify(db.email("change"), "new long passphrase")).isPresent();
        assertThat(auth.sessionsOf(me)).hasSize(1).first().satisfies(row -> assertThat(row.get("current")).isEqualTo(true));
    }

    @Test
    @DisplayName("QA-125 인증 전 계정이 연속 실패 뒤 맞는 비밀번호를 내면 login.unverified에 그 실패 수가 남는다(한 번만)")
    void unverifiedLoginRecordsPriorFailures() {
        long id = db.users.insert(db.email("unverified"), db.hasher.hash("correct horse battery"), "MEMBER", "FREE",
                false);
        AuthService auth = db.service(properties);
        auth.recordFailure(db.email("unverified"));
        auth.recordFailure(db.email("unverified"));

        assertThat(auth.login(db.email("unverified"), "correct horse battery", null).unverified()).isTrue();
        assertThat(auth.login(db.email("unverified"), "correct horse battery", null).unverified()).isTrue();

        assertThat(db.jdbc.queryForList("SELECT detail->>'priorFailures' FROM audit_log WHERE user_id = ? "
                + "AND action = 'login.unverified'", String.class, id)).as("직전 실패가 있던 첫 성공만").containsExactly("2");
    }

    @Test
    @DisplayName("QA-125 한 시간 상한을 넘긴 없는 계정 실패는 정기 작업이 시간이 끝난 뒤 요약 한 줄(사용자 번호 없음)로 남긴다")
    void flushWritesSuppressedSummary() {
        LoginThrottleTest.MutableClock clock = new LoginThrottleTest.MutableClock();
        clock.now = Instant.now();   // 실행마다 다른 시작 시각 — 이 테스트가 쓴 요약 행만 찾고 지웁니다
        String from = clock.now.toString();
        AuthService auth = new AuthService(properties, db.users, db.sessions, db.audit, db.hasher, clock);
        try {
            for (int i = 0; i < LoginFailureAudit.UNKNOWN_PER_WINDOW + 5; i++) {
                auth.recordFailure(db.email("nobody" + i));
            }
            auth.flushLoginFailureSummary();   // 시간이 끝나기 전 — 아직 쓰지 않음
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? "
                    + "AND detail->>'from' = ?", Integer.class, AuthService.LOGIN_FAILURE_SUPPRESSED, from)).isZero();

            clock.advance(LoginFailureAudit.WINDOW);
            auth.flushLoginFailureSummary();
            auth.flushLoginFailureSummary();   // 두 번 불러도 한 줄

            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'login.failure' "
                    + "AND detail->>'email' LIKE ?", Integer.class, db.prefix + "-nobody%"))
                    .isEqualTo(LoginFailureAudit.UNKNOWN_PER_WINDOW);
            assertThat(db.jdbc.queryForList("SELECT detail->>'count' FROM audit_log WHERE action = ? AND user_id IS NULL "
                    + "AND detail->>'from' = ?", String.class, AuthService.LOGIN_FAILURE_SUPPRESSED, from))
                    .containsExactly("5");
        } finally {
            db.jdbc.update("DELETE FROM audit_log WHERE action = ? AND detail->>'from' = ?",
                    AuthService.LOGIN_FAILURE_SUPPRESSED, from);
        }
    }

    @Test
    @DisplayName("계정이 없으면 OWNER_EMAIL·APP_PASSWORD로 운영자(PRO) 계정을 만든다 — 기존 사용 흐름 유지")
    void bootstrapsOwnerFromEnvironment() {
        UserStore users = mock(UserStore.class);
        when(users.ready()).thenReturn(true);
        when(users.count()).thenReturn(0L);
        properties.setOwnerEmail(" Owner@Example.COM ");
        properties.setPassword("app-password-from-env");
        AuthService auth = new AuthService(properties, users, mock(SessionStore.class), mock(AuditLogStore.class),
                new PasswordHasher());

        assertThat(auth.ensureOwner()).isTrue();
        verify(users).insert(eq("owner@example.com"), anyString(), eq("OWNER"), eq("PRO"), eq(true));
    }

    @Test
    @DisplayName("OWNER_EMAIL이 없거나 비밀번호가 공개된 옛 기본값이면 계정을 만들지 않는다(자리표시 기본값 없음)")
    void doesNotBootstrapWithoutSettings() {
        for (String[] setting : new String[][]{{"", "app-password"}, {"owner@example.com", ""},
                {"owner@example.com", "admin1234@"}}) {
            UserStore users = mock(UserStore.class);
            when(users.ready()).thenReturn(true);
            when(users.count()).thenReturn(0L);
            AppProperties props = new AppProperties();
            props.setOwnerEmail(setting[0]);
            props.setPassword(setting[1]);
            AuthService auth = new AuthService(props, users, mock(SessionStore.class), mock(AuditLogStore.class),
                    new PasswordHasher());

            assertThat(auth.ensureOwner()).isFalse();
            verify(users, never()).insert(anyString(), anyString(), anyString(), anyString(), anyBoolean());
            assertThat(auth.verify("owner@example.com", "app-password")).isEqualTo(Optional.empty());
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("QA-026 꺾쇠·표시 이름·여러 주소 표기는 이메일로 받지 않는다 — 메일이 다른 주소로 가지 않게")
    void rejectsAddressListAndDisplayNameForms() {
        for (String bad : new String[]{"x<attacker@evil.com>", "Name <a@b.com>", "a@b.com,c@d.com", "\"q\"@b.com",
                "a@b.com;c@d.com", "(c)a@b.com", "a@[127.0.0.1]"}) {
            org.assertj.core.api.Assertions.assertThat(AuthService.normalizeEmail(bad)).as(bad).isNull();
        }
        org.assertj.core.api.Assertions.assertThat(AuthService.normalizeEmail(" User.Name+tag@Example.COM "))
                .isEqualTo("user.name+tag@example.com");
    }
}

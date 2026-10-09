package com.macrodash.feature.auth;

import com.macrodash.store.EmailTokenStore;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 가입·메일 인증·재설정·삭제 — 실제 테스트 DB, 메일은 가짜(보낸 링크 토큰을 잡아 씀).
 */
class AccountServiceTest {

    private final AuthTestDatabase db = new AuthTestDatabase("acct");
    private final AccountMail mail = mock(AccountMail.class);
    private final EmailTokenStore tokens = new EmailTokenStore(db.jdbc);

    AccountServiceTest() {
        when(mail.configured()).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        db.cleanUp();
    }

    private AccountService service() {
        return new AccountService(db.users, db.sessions, tokens, db.audit, db.hasher, mail, Clock.systemUTC());
    }

    private String sentVerification(String email) {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mail).sendVerification(eq(email), token.capture());
        return token.getValue();
    }

    @Test
    @DisplayName("가입 → 인증 전에는 로그인 불가(unverified) → 링크로 인증 → 로그인. 링크는 한 번만")
    void signupVerifyLogin() {
        AccountService accounts = service();
        AuthService auth = db.service(new com.macrodash.config.AppProperties());
        String email = db.email("new");

        assertThat(accounts.signup(email, "a long enough passphrase", true, "10.0.0.1").status()).isEqualTo(200);
        AuthService.LoginResult before = auth.login(email, "a long enough passphrase", null);
        assertThat(before.issued()).isNull();
        assertThat(before.unverified()).isTrue();

        String token = sentVerification(email);
        assertThat(accounts.verify(token).status()).isEqualTo(200);
        assertThat(accounts.verify(token).status()).as("두 번째는 거절").isEqualTo(400);
        assertThat(auth.login(email, "a long enough passphrase", null).issued()).isNotNull();
        assertThat(db.users.findByEmail(email).orElseThrow().role()).isEqualTo("MEMBER");
        assertThat(db.users.findByEmail(email).orElseThrow().tier()).isEqualTo("FREE");
    }

    @Test
    @DisplayName("이미 있는 주소로 가입해도 같은 응답 — 주인에게만 안내 메일, 새 계정은 안 생김")
    void duplicateSignupLooksTheSame() {
        db.user("taken", "existing passphrase", "MEMBER", "FREE");
        AccountService accounts = service();

        AccountService.Result result = accounts.signup(db.email("taken"), "another passphrase!", true, "10.0.0.2");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.message()).isEqualTo(AccountService.SIGNUP_SENT);
        verify(mail).sendAlreadyRegistered(db.email("taken"));
        verify(mail, never()).sendVerification(anyString(), anyString());
    }

    @Test
    @DisplayName("동의·규칙·형식을 어기면 400, 메일 설정이 없으면 503")
    void signupValidation() {
        AccountService accounts = service();
        assertThat(accounts.signup(db.email("x"), "a long enough passphrase", false, "10.0.0.3").status()).isEqualTo(400);
        assertThat(accounts.signup(db.email("x"), "short", true, "10.0.0.3").status()).isEqualTo(400);
        assertThat(accounts.signup("not-email", "a long enough passphrase", true, "10.0.0.3").status()).isEqualTo(400);

        when(mail.configured()).thenReturn(false);
        assertThat(accounts.signup(db.email("x"), "a long enough passphrase", true, "10.0.0.3").status()).isEqualTo(503);
    }

    @Test
    @DisplayName("재설정: 없는 주소도 같은 응답, 링크로 새 비밀번호 → 모든 세션 해지, 링크는 한 번만")
    void passwordReset() {
        long id = db.user("reset", "old passphrase here", "MEMBER", "FREE");
        AccountService accounts = service();
        AuthService auth = db.service(new com.macrodash.config.AppProperties());
        AuthService.Issued session = auth.openSession(db.users.findById(id).orElseThrow(), null);

        assertThat(accounts.requestReset(db.email("nobody"), "10.0.0.4").message()).isEqualTo(AccountService.RESET_SENT);
        assertThat(accounts.requestReset(db.email("reset"), "10.0.0.4").message()).isEqualTo(AccountService.RESET_SENT);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mail).sendReset(eq(db.email("reset")), token.capture());

        assertThat(accounts.confirmReset(token.getValue(), "brand new passphrase").status()).isEqualTo(200);
        assertThat(accounts.confirmReset(token.getValue(), "another new passphrase").status()).isEqualTo(400);
        assertThat(auth.authenticate(session.token())).as("재설정하면 모든 세션 끊김").isEmpty();
        assertThat(auth.verify(db.email("reset"), "brand new passphrase")).isPresent();
    }

    @Test
    @DisplayName("가입·재설정 요청은 주소·이메일마다 시간당 5번까지")
    void mailRequestsAreRateLimited() {
        AccountService accounts = service();
        for (int i = 0; i < 5; i++) {
            assertThat(accounts.requestReset(db.email("rl"), "10.0.0.5").status()).isEqualTo(200);
        }
        assertThat(accounts.requestReset(db.email("rl"), "10.0.0.5").status()).isEqualTo(429);
    }

    @Test
    @DisplayName("계정 삭제: 비밀번호 재확인, 운영자는 못 지움, 지우면 세션·개인정보가 사라지고 감사 기록은 번호만 비움")
    void deleteAccount() {
        long member = db.user("del", "member passphrase", "MEMBER", "FREE");
        long owner = db.user("own", "owner passphrase!", "OWNER", "PRO");
        AccountService accounts = service();
        CurrentUser me = new CurrentUser(member, db.email("del"), "MEMBER", "FREE", null);

        assertThat(accounts.deleteAccount(me, "wrong passphrase").status()).isEqualTo(400);
        assertThat(accounts.deleteAccount(new CurrentUser(owner, db.email("own"), "OWNER", "PRO", null),
                "owner passphrase!").status()).isEqualTo(400);
        assertThat(accounts.deleteAccount(me, "member passphrase").status()).isEqualTo(200);

        assertThat(db.users.findById(member)).isEmpty();
        Integer orphaned = db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'account.deleted' AND user_id IS NULL "
                        + "AND at > now() - interval '1 minute'", Integer.class);
        assertThat(orphaned).isGreaterThanOrEqualTo(1);
    }
}

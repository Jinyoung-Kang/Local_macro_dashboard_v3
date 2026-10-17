package com.macrodash.feature.auth;

import com.macrodash.store.AuditLogStore;
import com.macrodash.store.EmailTokenStore;
import com.macrodash.store.SessionStore;
import com.macrodash.store.UserStore;
import com.macrodash.support.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * 가입·메일 인증·비밀번호 재설정·계정 삭제(명세 B1·B3·B4).
 *
 * <ul>
 *   <li>가입·재설정 요청은 이메일이 있든 없든 <b>같은 응답</b>입니다 — 화면으로 계정 존재를 알아낼 수 없게.
 *       이미 가입된 주소로 가입하면 그 주소의 주인에게만 안내 메일을 보냅니다.</li>
 *   <li>링크 토큰은 SHA-256만 저장하고, 한 번만 쓸 수 있습니다(인증 24시간, 재설정 30분).</li>
 *   <li>재설정하면 그 계정의 모든 세션을 끊습니다. 계정을 지우면 개인정보(이메일·세션·토큰)를 바로 지우고,
 *       감사 기록은 사용자 번호를 비운 채 남깁니다.</li>
 *   <li>메일 발송이 설정되지 않았으면(MAIL_HOST) 가입·재설정만 꺼집니다.</li>
 * </ul>
 */
@Service
public class AccountService {

    static final Duration VERIFY_TTL = Duration.ofHours(24);
    static final Duration RESET_TTL = Duration.ofMinutes(30);

    /** 처리 결과 — {@code status}는 HTTP 상태. */
    public record Result(int status, String message) {
        static Result ok(String message) {
            return new Result(200, message);
        }

        static Result bad(String message) {
            return new Result(400, message);
        }
    }

    static final String SIGNUP_SENT = "확인 메일을 보냈습니다. 메일의 링크를 열면 가입이 끝납니다(스팸함도 확인하세요).";
    static final String RESET_SENT = "가입된 주소라면 비밀번호 재설정 메일을 보냈습니다. 30분 안에 링크를 여세요.";
    static final String MAIL_OFF = "메일 발송이 설정되지 않아 이 기능을 쓸 수 없습니다. 운영자에게 문의하세요.";

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    /** 만료 뒤 이만큼 지난 메일 링크 토큰은 지웁니다. */
    static final Duration TOKEN_KEEP_AFTER_EXPIRY = Duration.ofDays(7);

    private final UserStore users;
    private final SessionStore sessions;
    private final EmailTokenStore tokens;
    private final AuditLogStore audit;
    private final PasswordHasher hasher;
    private final AccountMail mail;
    private final RateLimit mailRequests;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public AccountService(UserStore users, SessionStore sessions, EmailTokenStore tokens, AuditLogStore audit,
                          PasswordHasher hasher, AccountMail mail) {
        this(users, sessions, tokens, audit, hasher, mail, Clock.systemUTC());
    }

    AccountService(UserStore users, SessionStore sessions, EmailTokenStore tokens, AuditLogStore audit,
                   PasswordHasher hasher, AccountMail mail, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.tokens = tokens;
        this.audit = audit;
        this.hasher = hasher;
        this.mail = mail;
        // 주소마다, (주소·이메일)마다 시간당 5번 — 메일 폭탄·계정 탐색을 늦춥니다.
        // 기억하는 키 수에는 상한이 있습니다(RateLimit, QA-073).
        this.mailRequests = new RateLimit(5, Duration.ofHours(1), clock);
    }

    public boolean mailConfigured() {
        return mail.configured();
    }

    /** 가입 — 무료 회원, 메일 인증 전. 개인정보 수집 동의가 필요합니다. */
    public Result signup(String email, String password, boolean agreed, String client) {
        if (!mail.configured()) {
            return new Result(503, MAIL_OFF);
        }
        String normalized = AuthService.normalizeEmail(email);
        if (normalized == null) {
            return Result.bad("메일 주소 형식이 아닙니다.");
        }
        if (!agreed) {
            return Result.bad("개인정보 수집·이용에 동의해야 가입할 수 있습니다.");
        }
        String problem = PasswordPolicy.problem(password, normalized);
        if (problem != null) {
            return Result.bad(problem);
        }
        // 주소 키를 먼저 봅니다 — 주소 한도가 찬 뒤에도 이메일만 바꿔 보내면 이메일 키가 요청마다 쌓였습니다(QA-073).
        if (!mailRequests.allow("ip|" + client) || !mailRequests.allow(client + "|" + normalized)) {
            return new Result(429, "요청이 너무 많습니다. 한 시간 뒤에 다시 시도하세요.");
        }
        Optional<UserStore.User> existing = users.findByEmail(normalized);
        try {
            if (existing.isPresent()) {
                mail.sendAlreadyRegistered(normalized);
                return Result.ok(SIGNUP_SENT);   // 같은 응답 — 계정 존재를 드러내지 않습니다
            }
            long id;
            try {
                id = users.insert(normalized, hasher.hash(password), "MEMBER", "FREE", false);
            } catch (DuplicateKeyException race) {
                return Result.ok(SIGNUP_SENT);
            }
            audit.append(id, "signup", Map.of());
            mail.sendVerification(normalized, issue(id, "VERIFY", VERIFY_TTL));
            return Result.ok(SIGNUP_SENT);
        } catch (MailException e) {
            return new Result(502, "메일을 보내지 못했습니다. 잠시 뒤 다시 시도하세요.");
        }
    }

    /** 메일 인증 링크. */
    public Result verify(String token) {
        Optional<Long> userId = token == null ? Optional.empty() : tokens.consume(AuthService.hash(token), "VERIFY");
        if (userId.isEmpty()) {
            return Result.bad("링크가 만료됐거나 이미 쓴 링크입니다. 로그인 화면에서 다시 가입하거나 운영자에게 문의하세요.");
        }
        users.markVerified(userId.get());
        audit.append(userId.get(), "email.verified", Map.of());
        return Result.ok("메일 주소를 확인했습니다. 로그인하세요.");
    }

    /** 재설정 메일 요청 — 같은 응답(계정 존재를 드러내지 않음). 인증된 계정에만 보냅니다. */
    public Result requestReset(String email, String client) {
        if (!mail.configured()) {
            return new Result(503, MAIL_OFF);
        }
        String normalized = AuthService.normalizeEmail(email);
        if (normalized == null) {
            return Result.bad("메일 주소 형식이 아닙니다.");
        }
        // 주소 키 먼저(QA-073, 가입과 같음)
        if (!mailRequests.allow("ip|" + client) || !mailRequests.allow(client + "|" + normalized)) {
            return new Result(429, "요청이 너무 많습니다. 한 시간 뒤에 다시 시도하세요.");
        }
        Optional<UserStore.User> user = users.findByEmail(normalized);
        if (user.isPresent() && user.get().emailVerifiedAt() != null) {
            try {
                tokens.invalidate(user.get().id(), "RESET");
                mail.sendReset(normalized, issue(user.get().id(), "RESET", RESET_TTL));
                audit.append(user.get().id(), "password.reset.requested", Map.of());
            } catch (MailException e) {
                return new Result(502, "메일을 보내지 못했습니다. 잠시 뒤 다시 시도하세요.");
            }
        }
        return Result.ok(RESET_SENT);
    }

    /** 재설정 링크 + 새 비밀번호. 성공하면 그 계정의 모든 세션을 끊습니다. */
    public Result confirmReset(String token, String password) {
        if (token == null || token.isBlank()) {
            return Result.bad("링크가 올바르지 않습니다.");
        }
        Optional<Long> userId = tokens.consume(AuthService.hash(token), "RESET");
        if (userId.isEmpty()) {
            return Result.bad("링크가 만료됐거나 이미 쓴 링크입니다. 재설정을 다시 요청하세요.");
        }
        UserStore.User user = users.findById(userId.get()).orElse(null);
        if (user == null) {
            return Result.bad("계정을 찾을 수 없습니다.");
        }
        String problem = PasswordPolicy.problem(password, user.email());
        if (problem != null) {
            // 토큰은 이미 썼으므로 다시 요청해야 합니다 — 규칙을 먼저 화면에서 확인하게 합니다.
            return Result.bad(problem + " 링크는 한 번만 쓸 수 있어, 재설정을 다시 요청해야 합니다.");
        }
        users.updatePassword(user.id(), hasher.hash(password));
        int revoked = sessions.revokeAllForUser(user.id(), null);
        audit.append(user.id(), "password.reset", Map.of("sessionsRevoked", revoked));
        return Result.ok("비밀번호를 바꿨습니다. 모든 기기에서 로그아웃됐으니 새 비밀번호로 로그인하세요.");
    }

    /** 만료된 메일 링크 토큰 정리(QA-027). 실패하면(스키마 적용 전·DB 장애) 다음 주기에 다시 합니다. */
    @Scheduled(initialDelayString = "${dashboard.scheduler.auth-purge-initial-delay-ms:300000}",
            fixedDelayString = "${dashboard.scheduler.auth-purge-interval-ms:21600000}")
    public void purgeExpiredTokens() {
        if (!users.ready()) {
            return;
        }
        try {
            int removed = tokens.purgeExpiredOlderThan(TOKEN_KEEP_AFTER_EXPIRY);
            if (removed > 0) {
                log.info("만료된 메일 토큰 {}건 정리", removed);
            }
        } catch (RuntimeException e) {
            log.warn("메일 토큰 정리 실패 — 다음 주기에 다시 시도합니다: {}", e.toString());
        }
    }

    /** 계정 삭제 — 비밀번호를 다시 확인합니다. 운영자 계정은 지울 수 없습니다(운영자가 사라지면 아무도 관리 못 함). */
    /** 탈퇴 재확인에서 비밀번호가 틀렸을 때의 문구 — 컨트롤러가 재확인 실패(지연 기록)를 가르는 데 씀. */
    static final String WRONG_PASSWORD = "비밀번호가 올바르지 않습니다.";

    public Result deleteAccount(CurrentUser user, String password) {
        UserStore.User stored = users.findById(user.id()).orElse(null);
        if (stored == null || !hasher.matches(password, stored.passwordHash())) {
            return Result.bad(WRONG_PASSWORD);
        }
        if ("OWNER".equals(stored.role())) {
            return Result.bad("운영자 계정은 지울 수 없습니다.");
        }
        audit.append(stored.id(), "account.deleted", Map.of());
        users.delete(stored.id());   // 세션·토큰은 함께 지워지고 감사 기록은 사용자 번호만 비워집니다
        return Result.ok("계정과 개인정보(이메일·세션·알림 설정)를 지웠습니다.");
    }

    private String issue(long userId, String purpose, Duration ttl) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.create(AuthService.hash(token), userId, purpose, Instant.now().plus(ttl));
        return token;
    }
}

package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import com.macrodash.store.AuditLogStore;
import com.macrodash.store.SessionStore;
import com.macrodash.store.UserStore;
import com.macrodash.support.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 계정 로그인과 DB 세션(ADR 0010).
 *
 * <p>예전에는 비밀번호 하나({@code APP_PASSWORD})와 서명 토큰(JWT)이었습니다. 로그아웃한 토큰을 막는 목록이
 * 메모리에 있어 재시작하면 사라졌고, 사용자가 한 명뿐이라 등급·권한을 나눌 수 없었습니다.
 *
 * <ul>
 *   <li>비밀번호는 Argon2id 해시만 둡니다. 없는 이메일도 가짜 해시와 비교해 응답 시간으로 계정 존재를 드러내지 않습니다.</li>
 *   <li>쿠키에는 32바이트 무작위 토큰, DB에는 그 SHA-256만 둡니다. DB가 새어도 쿠키를 만들 수 없습니다.</li>
 *   <li>처음 기동할 때 계정이 없으면 {@code OWNER_EMAIL}·{@code APP_PASSWORD}로 운영자 계정을 만듭니다 —
 *       지금까지의 사용 흐름이 이어집니다. 둘 중 하나라도 없으면 만들지 않고 무엇을 넣을지 로그로 알립니다.</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    public static final String COOKIE_NAME = "macro_session";

    /** 저장소·문서에 적혀 있던 옛 기본 비밀번호. 누구나 아는 값이라 운영자 계정에 쓰지 않습니다. */
    static final String PUBLIC_DEFAULT_PASSWORD = "admin1234@";
    /** 마지막 사용 시각 갱신 간격 — 요청마다 쓰지 않습니다. */
    static final Duration TOUCH_GAP = Duration.ofMinutes(5);
    static final int MAX_USER_AGENT = 120;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}$");

    private final AppProperties properties;
    private final UserStore users;
    private final SessionStore sessions;
    private final AuditLogStore audit;
    private final PasswordHasher hasher;
    private final SecureRandom random = new SecureRandom();

    public AuthService(AppProperties properties, UserStore users, SessionStore sessions, AuditLogStore audit,
                       PasswordHasher hasher) {
        this.properties = properties;
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
        this.hasher = hasher;
    }

    /** 로그인 성공 — 쿠키에 넣을 토큰과 사용자. */
    public record Issued(String token, CurrentUser user) {
    }

    // ------------------------------------------------------------------ 운영자 계정 만들기
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapOnStartup() {
        try {
            ensureOwner();
        } catch (RuntimeException e) {
            log.warn("운영자 계정 확인 실패 — 첫 로그인 때 다시 시도합니다: {}", e.toString());
        }
    }

    /**
     * 계정이 하나도 없으면 운영자 계정을 만듭니다. 테이블이 아직 없거나(수집기가 V4 적용 전) 설정이 모자라면
     * 만들지 않습니다.
     *
     * @return 계정이 하나 이상 있으면 true
     */
    synchronized boolean ensureOwner() {
        if (!users.ready()) {
            log.warn("계정 테이블(V4)이 아직 없습니다. 수집기가 기동하며 스키마를 적용하면 로그인할 수 있습니다.");
            return false;
        }
        if (users.count() > 0) {
            return true;
        }
        String email = normalizeEmail(properties.getOwnerEmail());
        String password = properties.getPassword();
        if (email == null || password == null || password.isBlank() || PUBLIC_DEFAULT_PASSWORD.equals(password)) {
            log.warn("운영자 계정이 없습니다. .env에 OWNER_EMAIL(로그인 아이디로 쓸 이메일)과 APP_PASSWORD를 넣고 "
                    + "make up 하세요 — 그 전에는 로그인할 수 없습니다. (make setup이 APP_PASSWORD를 만들어 줍니다)");
            return false;
        }
        try {
            long id = users.insert(email, hasher.hash(password), "OWNER", "PRO", true);
            audit.append(id, "owner.created", Map.of("from", "APP_PASSWORD"));
            log.info("운영자 계정을 만들었습니다(OWNER_EMAIL). 이후 비밀번호는 화면에서 바꾸고, APP_PASSWORD는 더 읽지 않습니다.");
        } catch (DuplicateKeyException e) {
            // 두 인스턴스가 동시에 만들려 한 경우 — 이미 있으면 됩니다.
        }
        return true;
    }

    // ------------------------------------------------------------------ 로그인·세션
    /**
     * 이메일·비밀번호 확인.
     *
     * @return 맞으면 사용자 번호, 아니면 비어 있음. 없는 이메일도 같은 시간이 걸립니다.
     */
    public Optional<UserStore.User> verify(String email, String password) {
        if (!ensureOwner()) {
            hasher.matchesDummy(password);
            return Optional.empty();
        }
        String normalized = normalizeEmail(email);
        Optional<UserStore.User> user = normalized == null ? Optional.empty() : users.findByEmail(normalized);
        if (user.isEmpty()) {
            hasher.matchesDummy(password);
            return Optional.empty();
        }
        return hasher.matches(password, user.get().passwordHash()) ? user : Optional.empty();
    }

    /** 확인된 사용자에게 새 세션을 엽니다. */
    public Issued openSession(UserStore.User user, String userAgent) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID sessionId = sessions.create(user.id(), hash(token), Instant.now().plusSeconds(sessionSeconds()),
                trimAgent(userAgent));
        audit.append(user.id(), "login.success", Map.of());
        return new Issued(token, new CurrentUser(user.id(), user.email(), user.role(), user.tier(), sessionId));
    }

    /**
     * 로그인 결과. 비밀번호가 맞아도 메일 인증 전이면 세션을 열지 않습니다({@code unverified}).
     * 비밀번호가 틀리면 둘 다 비어 있습니다 — 인증 여부는 비밀번호를 아는 사람에게만 알려 줍니다.
     */
    public record LoginResult(Issued issued, boolean unverified) {
    }

    public LoginResult login(String email, String password, String userAgent) {
        Optional<UserStore.User> user = verify(email, password);
        if (user.isEmpty()) {
            return new LoginResult(null, false);
        }
        if (user.get().emailVerifiedAt() == null) {
            return new LoginResult(null, true);
        }
        return new LoginResult(openSession(user.get(), userAgent), false);
    }

    /** 로그인 실패를 감사 기록에 남깁니다(이메일만, 비밀번호는 남기지 않음). */
    public void recordFailure(String email) {
        if (!users.ready()) {
            return;
        }
        String normalized = normalizeEmail(email);
        Long userId = normalized == null ? null : users.findByEmail(normalized).map(UserStore.User::id).orElse(null);
        Map<String, Object> detail = new LinkedHashMap<>();
        if (userId == null && normalized != null) {
            detail.put("email", normalized);   // 없는 계정 시도는 이메일로만 남습니다
        }
        audit.append(userId, "login.failure", detail);
    }

    /** 쿠키 토큰 → 사용자. 해지·만료·없는 토큰이면 비어 있음. */
    public Optional<CurrentUser> authenticate(String token) {
        if (token == null || token.isBlank() || token.length() > 100 || !users.ready()) {
            return Optional.empty();
        }
        return sessions.findActive(hash(token)).map(active -> {
            if (active.lastSeenAt() == null || active.lastSeenAt().isBefore(Instant.now().minus(TOUCH_GAP))) {
                sessions.touch(active.sessionId(), TOUCH_GAP);
            }
            return new CurrentUser(active.userId(), active.email(), active.role(), active.tier(), active.sessionId());
        });
    }

    public void logout(String token) {
        authenticate(token).ifPresent(user -> sessions.revoke(user.sessionId()));
    }

    /** 이 사용자의 다른 기기 세션을 모두 끊습니다(지금 세션은 남김). */
    public int logoutOthers(CurrentUser user) {
        int count = sessions.revokeAllForUser(user.id(), user.sessionId());
        audit.append(user.id(), "logout.others", Map.of("sessions", count));
        return count;
    }

    public List<Map<String, Object>> sessionsOf(CurrentUser user) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionStore.Listed listed : sessions.activeForUser(user.id())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", listed.sessionId().toString());
            row.put("current", listed.sessionId().equals(user.sessionId()));
            row.put("createdAt", String.valueOf(listed.createdAt()));
            row.put("lastSeenAt", String.valueOf(listed.lastSeenAt()));
            row.put("expiresAt", String.valueOf(listed.expiresAt()));
            row.put("userAgent", listed.userAgent());
            out.add(row);
        }
        return out;
    }

    /**
     * 비밀번호 변경 — 지금 비밀번호를 확인하고, 다른 기기 세션은 모두 끊습니다.
     *
     * @return 문제가 있으면 사람이 읽는 이유, 성공이면 null
     */
    public String changePassword(CurrentUser user, String current, String next) {
        UserStore.User stored = users.findById(user.id()).orElse(null);
        if (stored == null || !hasher.matches(current, stored.passwordHash())) {
            return "지금 비밀번호가 올바르지 않습니다.";
        }
        String problem = PasswordPolicy.problem(next, stored.email());
        if (problem != null) {
            return problem;
        }
        if (hasher.matches(next, stored.passwordHash())) {
            return "지금과 다른 비밀번호를 쓰세요.";
        }
        users.updatePassword(user.id(), hasher.hash(next));
        int others = sessions.revokeAllForUser(user.id(), user.sessionId());
        audit.append(user.id(), "password.changed", Map.of("otherSessionsRevoked", others));
        return null;
    }

    public long sessionSeconds() {
        return properties.getSessionMinutes() * 60;
    }

    // ------------------------------------------------------------------ 보조
    /** 이메일을 비교용으로 다듬습니다. 형식이 아니면 null. */
    static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim().toLowerCase(Locale.ROOT);
        return EMAIL.matcher(trimmed).matches() ? trimmed : null;
    }

    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }

    private static String trimAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() > MAX_USER_AGENT ? userAgent.substring(0, MAX_USER_AGENT) : userAgent;
    }
}

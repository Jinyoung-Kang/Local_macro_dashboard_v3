package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import com.macrodash.support.CurrentUser;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 로그인 · 세션 확인 · 로그아웃 · 세션 목록 · 비밀번호 변경 (계정·DB 세션, ADR 0010).
 *
 * <p>세션 쿠키 속성
 * <ul>
 *   <li>HttpOnly — 스크립트가 읽을 수 없습니다(XSS로 토큰이 새지 않음).</li>
 *   <li>SameSite=Strict — 다른 사이트에서 시작된 요청에는 쿠키가 실리지 않습니다(CSRF 방어).
 *       화면(:3000)과 API(:8080)는 포트만 다르고 같은 사이트라 정상 동작합니다.</li>
 *   <li>Secure — dashboard.cookie-secure=true일 때만(HTTPS 전용 운영).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginThrottle throttle;
    private final AppProperties properties;

    public AuthController(AuthService authService, LoginThrottle throttle, AppProperties properties) {
        this.authService = authService;
        this.throttle = throttle;
        this.properties = properties;
    }

    public record LoginRequest(String email, String password) {
    }

    public record PasswordRequest(String current, String next) {
    }

    /**
     * 이메일·비밀번호를 확인하고 세션 쿠키를 내려 줍니다.
     *
     * <p>연속 실패는 {@link LoginThrottle}이 (접속 주소, 이메일)마다 느리게 처리합니다 — 잠금이 아니라 지연이라
     * 맞는 비밀번호는 그래도 통과합니다. 없는 이메일과 틀린 비밀번호는 같은 응답입니다(계정 존재를 드러내지 않음).
     *
     * @return 200 성공 · 401 이메일 또는 비밀번호 틀림 · 429 연속 실패(Retry-After) 또는 다른 느린 시도가 진행 중
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest request,
                                                     HttpServletRequest http) {
        String email = request == null ? null : request.email();
        String password = request == null ? null : request.password();
        String key = http.getRemoteAddr() + "|" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
        AtomicReference<AuthService.Issued> issued = new AtomicReference<>();
        String userAgent = http.getHeader(HttpHeaders.USER_AGENT);
        LoginThrottle.Outcome outcome = throttle.attempt(key, () -> {
            issued.set(authService.login(email, password, userAgent).orElse(null));
            return issued.get() != null;
        });

        if (outcome.busy()) {
            return tooMany(outcome.retryAfter(), "다른 로그인 시도를 처리하는 중입니다. 잠시 후 다시 시도하세요.");
        }
        if (!outcome.accepted()) {
            authService.recordFailure(email);
            if (!outcome.retryAfter().isZero()) {
                long seconds = seconds(outcome.retryAfter());
                return tooMany(outcome.retryAfter(),
                        "로그인 실패가 반복됐습니다. " + seconds + "초 뒤에 다시 시도하세요.");
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "message", "이메일 또는 비밀번호가 올바르지 않습니다."));
        }

        ResponseCookie cookie = sessionCookie(issued.get().token(), authService.sessionSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("ok", true, "user", userMap(issued.get().user())));
    }

    /**
     * 세션 확인 — 로그인돼 있으면 사용자(이메일·역할·등급)와 읽기 모드를 줍니다. 화면은 이 값으로 메뉴를 고릅니다.
     * 막는 것은 백엔드(경로별 접근 정책)이고, 화면은 보여 줄지만 정합니다.
     */
    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        Optional<CurrentUser> user = authService.authenticate(readToken(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authenticated", user.isPresent());
        user.ifPresent(current -> {
            out.put("readMode", properties.resolvedReadMode().name().toLowerCase(Locale.ROOT));
            out.put("user", userMap(current));
        });
        return out;
    }

    /** 로그아웃 — 쿠키를 지우고, 그 세션도 서버에서 끊습니다(쿠키 값이 남아 있어도 못 씀, 재시작 후에도). */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        authService.logout(readToken(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString())
                .body(Map.of("ok", true));
    }

    /** 다른 기기에서 모두 로그아웃(지금 기기는 남김). */
    @PostMapping("/logout-all")
    public Map<String, Object> logoutOthers(HttpServletRequest request) {
        int count = authService.logoutOthers(CurrentUser.of(request));
        return Map.of("ok", true, "revoked", count);
    }

    /** 내 로그인 세션 목록(기기·마지막 사용 시각). */
    @GetMapping("/sessions")
    public Map<String, Object> sessions(HttpServletRequest request) {
        return Map.of("sessions", authService.sessionsOf(CurrentUser.of(request)));
    }

    /** 비밀번호 변경 — 지금 비밀번호 확인, 규칙 검사, 다른 기기 세션은 끊음. */
    @PostMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody(required = false) PasswordRequest body,
                                                              HttpServletRequest request) {
        String problem = body == null ? "요청 본문이 없습니다."
                : authService.changePassword(CurrentUser.of(request), body.current(), body.next());
        if (problem != null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", problem));
        }
        return ResponseEntity.ok(Map.of("ok", true, "message", "비밀번호를 바꿨습니다. 다른 기기의 로그인은 끊었습니다."));
    }

    private static Map<String, Object> userMap(CurrentUser user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", user.email());
        out.put("role", user.role());
        out.put("tier", user.tier());
        return out;
    }

    /** 세션 쿠키 한 곳에서만 만듭니다(로그인·로그아웃 속성이 어긋나면 삭제가 안 됩니다). */
    private ResponseCookie sessionCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(AuthService.COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    private static long seconds(Duration wait) {
        return Math.max(1, (wait.toMillis() + 999) / 1000);
    }

    private static ResponseEntity<Map<String, Object>> tooMany(Duration wait, String message) {
        long seconds = seconds(wait);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(Map.of("ok", false, "retryAfterSeconds", seconds, "message", message));
    }

    /** 세션 쿠키 값을 꺼냅니다. 없으면 null. */
    public static String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthService.COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}

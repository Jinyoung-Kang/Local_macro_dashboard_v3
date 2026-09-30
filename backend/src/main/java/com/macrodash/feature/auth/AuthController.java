package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
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

/**
 * 로그인 · 세션 확인 · 로그아웃.
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

    public record LoginRequest(String password) {
    }

    /**
     * 비밀번호를 확인하고 세션 쿠키를 내려 줍니다.
     *
     * @return 200 성공 · 401 비밀번호 틀림 · 429 연속 실패로 잠김(Retry-After 헤더에 남은 초)
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest request,
                                                     HttpServletRequest http) {
        String client = http.getRemoteAddr();
        Duration wait = throttle.retryAfter(client);
        if (!wait.isZero()) {
            return tooMany(wait);
        }

        if (request == null || !authService.passwordMatches(request.password())) {
            Duration lock = throttle.recordFailure(client);
            if (!lock.isZero()) {
                return tooMany(lock);
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "message", "비밀번호가 올바르지 않습니다."));
        }

        throttle.recordSuccess(client);
        ResponseCookie cookie = sessionCookie(authService.issueToken(), authService.sessionSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("ok", true));
    }

    /**
     * 세션 확인.
     *
     * <p>로그인돼 있으면 읽기 모드도 함께 줍니다. 화면(사이드바)은 이 값 하나를 보여 주려고
     * 페이지를 열 때마다 무거운 {@code /api/status}(수집기 상태 + DB 집계)를 불렀습니다.
     */
    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        boolean authenticated = authService.isValid(readToken(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("authenticated", authenticated);
        if (authenticated) {
            out.put("readMode", properties.resolvedReadMode().name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** 로그아웃 — 쿠키를 지우고, 그 토큰도 서버에서 거부합니다(쿠키 값이 남아 있어도 못 씀). */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        authService.revoke(readToken(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString())
                .body(Map.of("ok", true));
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

    private static ResponseEntity<Map<String, Object>> tooMany(Duration wait) {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(Map.of("ok", false, "retryAfterSeconds", seconds,
                        "message", "로그인 실패가 반복돼 " + seconds + "초 동안 잠겼습니다."));
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

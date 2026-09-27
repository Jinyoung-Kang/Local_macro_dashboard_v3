package com.macrodash.service;

import com.macrodash.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 간이 인증 (비밀번호 잠금).
 *
 * <p>구버전은 Streamlit 세션 상태에 불린 하나를 두는 방식이었고, 화면이
 * 하나의 프로세스였기에 가능했습니다. 지금은 프런트·백엔드가 분리돼 있으므로
 * 서명된 토큰을 씁니다.
 *
 * <p>토큰은 <b>httpOnly 쿠키</b>로 내려갑니다. 브라우저 스크립트가 읽을 수
 * 없으므로 XSS로 토큰이 유출되지 않습니다.
 *
 * <p>비밀번호 비교는 {@link MessageDigest#isEqual}로 <b>길이에 관계없이 일정
 * 시간</b>에 수행합니다. 문자열 {@code equals}는 앞에서부터 비교하다 다르면
 * 즉시 끝나 타이밍 차이가 생깁니다.
 *
 * <p>토큰을 끝내는 두 가지 (SEC-07) — 예전에는 둘 다 만료(12시간)까지 토큰이 계속 통했습니다.
 * <ul>
 *   <li>비밀번호 변경: 토큰에 비밀번호 지문(서명 키로 만든 HMAC, 비밀번호 자체는 담지 않음)을
 *       넣습니다. .env에서 비밀번호를 바꾸고 재시작하면 지문이 달라져 이전 토큰이 모두 무효입니다.</li>
 *   <li>로그아웃: 그 토큰의 번호(jti)를 만료 시각까지 거부합니다. 목록은 메모리에만 있어
 *       백엔드를 재시작하면 비워집니다(그 사이 로그아웃한 토큰은 원래 만료까지 다시 유효).</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    public static final String COOKIE_NAME = "macro_session";

    private final AppProperties properties;
    private final SecretKey key;
    /** 현재 비밀번호의 지문. 토큰의 {@code pw} 클레임과 같아야 유효합니다. */
    private final String passwordStamp;
    /** 로그아웃한 토큰 번호(jti) → 그 토큰의 만료 시각. 만료가 지나면 지웁니다. */
    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    /** 저장소에 공개된 기본값들. 이 값으로 서명하면 누구나 토큰을 위조할 수 있습니다. */
    static final Set<String> KNOWN_PLACEHOLDERS = Set.of(
            "change-me-please-change-me-please-32b");
    static final String DEFAULT_PASSWORD = "admin1234@";

    public AuthService(AppProperties properties) {
        this.properties = properties;
        byte[] secret = signingSecret(properties.getJwtSecret());
        this.key = Keys.hmacShaKeyFor(secret);
        this.passwordStamp = passwordStamp(secret, properties.getPassword());
        if (DEFAULT_PASSWORD.equals(properties.getPassword())) {
            log.warn("APP_PASSWORD가 기본값입니다. 같은 네트워크의 누구나 로그인할 수 있으니 .env에서 바꾸세요.");
        }
    }

    /**
     * 서명 키 바이트를 정합니다.
     *
     * <p>설정값이 공개된 기본값이거나 32바이트(HS256 최소) 미만이면 <b>기동할 때마다
     * 무작위 키</b>를 씁니다. 예전에는 짧은 값을 정해진 바이트로 채웠는데, 그러면
     * 키가 사실상 공개돼 누구나 세션 토큰을 만들 수 있었습니다.
     *
     * <p>주의사항 — 무작위 키를 쓰면 재시작할 때 기존 세션이 모두 풀립니다.
     * 계속 로그인 상태를 유지하려면 JWT_SECRET을 설정하세요(make setup이 만들어 줍니다).
     *
     * @param configured 설정된 JWT_SECRET
     * @return HMAC 키 바이트(32바이트 이상)
     */
    static byte[] signingSecret(String configured) {
        byte[] secret = configured == null ? new byte[0] : configured.getBytes(StandardCharsets.UTF_8);
        if (secret.length >= 32 && !KNOWN_PLACEHOLDERS.contains(configured)) {
            return secret;
        }
        log.warn("JWT_SECRET이 비었거나 기본값·32바이트 미만입니다. 이번 실행 동안만 쓰는 무작위 "
                + "키로 서명합니다(재시작하면 다시 로그인해야 합니다).");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return random;
    }

    public boolean passwordMatches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                properties.getPassword().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 비밀번호 지문 — 서명 키로 만든 HMAC의 앞부분.
     *
     * <p>토큰은 누구나 풀어 볼 수 있으므로(서명만 돼 있음) 비밀번호나 단순 해시를 넣지 않습니다.
     * 서명 키 없이는 이 값에서 비밀번호를 맞춰 볼 수 없습니다.
     */
    static String passwordStamp(byte[] secret, String password) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(("password:" + (password == null ? "" : password))
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256을 쓸 수 없습니다", e);
        }
    }

    public String issueToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("dashboard-user")
                .id(UUID.randomUUID().toString())
                .claim("pw", passwordStamp)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getSessionMinutes() * 60)))
                .signWith(key)
                .compact();
    }

    public boolean isValid(String token) {
        Claims claims = verifiedClaims(token);
        if (claims == null) {
            return false;
        }
        boolean unexpired = claims.getExpiration() == null
                || claims.getExpiration().toInstant().isAfter(Instant.now());
        return unexpired
                && passwordStamp.equals(claims.get("pw", String.class))
                && (claims.getId() == null || !revoked.containsKey(claims.getId()));
    }

    /**
     * 로그아웃 — 이 토큰을 만료 시각까지 거부합니다. 서명이 맞지 않거나 비어 있으면 무시합니다.
     *
     * @param token 세션 쿠키 값 (null 허용)
     */
    public void revoke(String token) {
        Claims claims = verifiedClaims(token);
        if (claims == null || claims.getId() == null) {
            return;
        }
        Instant now = Instant.now();
        Instant expires = claims.getExpiration() == null
                ? now.plusSeconds(sessionSeconds()) : claims.getExpiration().toInstant();
        revoked.values().removeIf(until -> until.isBefore(now));   // 만료된 항목은 더 막을 필요가 없습니다
        revoked.put(claims.getId(), expires);
    }

    /** 서명이 맞는 토큰의 내용. 위조·형식 오류·빈 값이면 null. */
    private Claims verifiedClaims(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    public long sessionSeconds() {
        return properties.getSessionMinutes() * 60;
    }
}

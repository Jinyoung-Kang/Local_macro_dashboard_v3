package com.macrodash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 애플리케이션 설정.
 *
 * <p>읽기 모드는 구버전 {@code DASHBOARD_READ_MODE}를 그대로 계승합니다.
 * <ul>
 *   <li>{@code auto} — 저장본이 신선하면 사용, 오래됐으면 수집기에 수집을 요청</li>
 *   <li>{@code store_only} — 저장본만 사용. 오래됐어도 그대로 보여주고 외부를
 *       <b>절대</b> 기다리지 않음 (수집기를 항상 켜 두는 운영에 적합)</li>
 *   <li>{@code live_only} — 저장 계층을 건너뛰고 항상 수집 요청 (디버깅용)</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "dashboard")
public class AppProperties {

    /**
     * 처음 만드는 운영자 계정의 비밀번호(APP_PASSWORD). 계정이 생긴 뒤에는 읽지 않습니다 — 비밀번호는 화면에서
     * 바꿉니다(ADR 0010).
     */
    private String password = "";

    /** 처음 만드는 운영자 계정의 이메일(로그인 아이디, OWNER_EMAIL). 기본값 없음. */
    private String ownerEmail = "";

    /** 계정 메일의 보낸 사람(MAIL_FROM). 비면 SMTP 계정(MAIL_USERNAME). */
    private String mailFrom = "";

    /** 메일 속 링크가 가리킬 화면 주소(PUBLIC_URL). 비면 FRONTEND_ORIGIN의 첫 값. */
    private String publicUrl = "";

    /**
     * 세션 쿠키에 Secure 속성을 붙일지. HTTPS로만 접속하는 경우에 켜세요.
     * 로컬 http://에서 켜면 브라우저가 쿠키를 저장하지 않아 로그인이 되지 않습니다.
     */
    private boolean cookieSecure = false;

    /** 세션 유효 시간(분). */
    private long sessionMinutes = 720;

    /** 읽기 모드: auto | store_only | live_only */
    private String readMode = "auto";

    /** 수집기(FastAPI) 주소. */
    private String collectorUrl = "http://localhost:8000";

    /** 수집기 호출용 토큰 (수집기의 COLLECTOR_API_TOKEN과 같아야 합니다). */
    private String collectorToken = "";

    /** auto 모드에서 수집기를 기다리는 최대 시간(초). */
    private int collectorTimeoutSeconds = 90;

    /** 프런트엔드 오리진 (CORS 허용). */
    private String frontendOrigin = "http://localhost:3000";
    /** 화면 포트. 어느 호스트에서 열든 이 포트의 origin은 허용합니다(휴대폰에서 LAN IP로 접속). */
    private int frontendPort = 3000;

    /**
     * 데모 열람 토큰(DASHBOARD_DEMO_VIEWER_TOKEN, ADR 0013). 비면 기능이 꺼집니다(기본·로컬).
     * 앞단 프록시가 접근 코드를 확인한 요청에 {@code X-Demo-Viewer} 헤더로 이 값을 실어 보냅니다.
     * 32자보다 짧으면 기동하지 않습니다({@code feature.auth.DemoViewer}).
     */
    private String demoViewerToken = "";

    public ReadMode resolvedReadMode() {
        return ReadMode.from(readMode);
    }

    public enum ReadMode {
        AUTO, STORE_ONLY, LIVE_ONLY;

        public static ReadMode from(String raw) {
            if (raw == null) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "store_only", "store-only" -> STORE_ONLY;
                case "live_only", "live-only" -> LIVE_ONLY;
                default -> AUTO;
            };
        }
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getMailFrom() {
        return mailFrom;
    }

    public void setMailFrom(String mailFrom) {
        this.mailFrom = mailFrom;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }

    /** 메일 링크의 기준 주소 — PUBLIC_URL, 없으면 FRONTEND_ORIGIN의 첫 값(끝 / 없음). */
    public String linkBase() {
        String base = publicUrl != null && !publicUrl.isBlank() ? publicUrl.trim()
                : frontendOrigin.split(",")[0].trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public long getSessionMinutes() {
        return sessionMinutes;
    }

    public void setSessionMinutes(long sessionMinutes) {
        this.sessionMinutes = sessionMinutes;
    }

    public String getReadMode() {
        return readMode;
    }

    public void setReadMode(String readMode) {
        this.readMode = readMode;
    }

    public String getCollectorUrl() {
        return collectorUrl;
    }

    public void setCollectorUrl(String collectorUrl) {
        this.collectorUrl = collectorUrl;
    }

    public String getCollectorToken() {
        return collectorToken;
    }

    public void setCollectorToken(String collectorToken) {
        this.collectorToken = collectorToken;
    }

    public int getCollectorTimeoutSeconds() {
        return collectorTimeoutSeconds;
    }

    public void setCollectorTimeoutSeconds(int collectorTimeoutSeconds) {
        this.collectorTimeoutSeconds = collectorTimeoutSeconds;
    }

    public String getFrontendOrigin() {
        return frontendOrigin;
    }

    public int getFrontendPort() {
        return frontendPort;
    }

    public void setFrontendPort(int frontendPort) {
        this.frontendPort = frontendPort;
    }

    public void setFrontendOrigin(String frontendOrigin) {
        this.frontendOrigin = frontendOrigin;
    }

    public String getDemoViewerToken() {
        return demoViewerToken;
    }

    public void setDemoViewerToken(String demoViewerToken) {
        this.demoViewerToken = demoViewerToken;
    }
}

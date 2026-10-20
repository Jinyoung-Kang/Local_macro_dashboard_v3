package com.macrodash.feature.auth;

import com.macrodash.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 로컬 무로그인 모드(ADR 0016) — 이 맥의 Docker Compose에서는 로그인 화면 없이 첫 운영자 계정으로 엽니다.
 *
 * <p>로그인을 빼는 대신 "이 맥에서 바로 온 요청"만 운영자로 봅니다({@link #isLocalRequest}).
 * <ul>
 *   <li>요청의 Host가 localhost·127.0.0.1·[::1]이고, 앞단 프록시가 붙이는 헤더({@link #PROXY_HEADERS})가 하나도 없을 때만.
 *       접속 주소(remoteAddr)가 아니라 Host로 보는 이유: Docker Desktop은 모든 접속을 같은 게이트웨이 주소로 보여 줘
 *       맥과 LAN을 가를 수 없습니다. 포트는 compose가 127.0.0.1에만 열고, 다른 이름으로 온 요청(DNS 리바인딩 등)은
 *       Host가 달라 평소처럼 로그인이 필요합니다.</li>
 *   <li>쿠키가 없으니 SameSite가 막아 주지 않습니다 — 다른 사이트에서 온 요청은 세션 필터가 Origin·Sec-Fetch-Site로
 *       막습니다({@code web.WebConfig.SessionFilter}).</li>
 *   <li>켜져 있는데 웹 배포의 신호가 하나라도 있으면 기동하지 않습니다({@link #check}). AWS·QA 오버레이는 끔을 고정하지만,
 *       설정을 잘못 섞어 공개 배포에서 로그인이 빠지는 일을 한 겹 더 막습니다.</li>
 * </ul>
 */
@Component
public class LocalNoLogin {

    private static final Logger log = LoggerFactory.getLogger(LocalNoLogin.class);

    /** 이 맥을 가리키는 이름. 서블릿의 serverName은 IPv6를 대괄호와 함께 줄 수 있어 둘 다 둡니다. */
    static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    /**
     * 앞단 프록시(Caddy·nginx·터널 등)를 거쳤다는 표시 — 하나라도 있으면 이 맥에서 바로 온 요청이 아닙니다. 이 맥의 브라우저는
     * 이 헤더들을 보내지 않습니다. 다만 Host를 127.0.0.1로 바꾸고 아무 표시도 붙이지 않는 프록시는 가를 수 없어, 무로그인
     * 스택 앞에 프록시·터널을 두지 않는 것이 전제입니다(ADR 0016).
     */
    static final List<String> PROXY_HEADERS = List.of("X-Forwarded-For", "Forwarded", "X-Forwarded-Host",
            "X-Forwarded-Proto", "X-Forwarded-Port", "X-Real-IP", "Via", "CF-Connecting-IP", "True-Client-IP",
            DemoViewer.HEADER);

    private final boolean enabled;

    @Autowired
    public LocalNoLogin(AppProperties properties, Environment environment) {
        this(properties, forwardHeadersStrategy(environment));
    }

    LocalNoLogin(AppProperties properties, String forwardHeadersStrategy) {
        check(properties, forwardHeadersStrategy);
        this.enabled = properties.isLocalNoLogin();
        if (enabled) {
            log.warn("로그인 없이 운영자로 엽니다 — 이 맥(127.0.0.1)에서만 (DASHBOARD_LOCAL_NO_LOGIN, ADR 0016)");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 이 맥에서 앞단을 거치지 않고 바로 온 요청인가. 기능이 켜져 있는지는 보지 않습니다(부르는 쪽이 봄).
     */
    public static boolean isLocalRequest(HttpServletRequest request) {
        for (String header : PROXY_HEADERS) {
            if (request.getHeader(header) != null) {
                return false;
            }
        }
        String host = request.getServerName();
        return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * 켜져 있을 때 웹 배포의 신호가 있으면 기동을 멈춥니다. 꺼져 있으면 아무것도 보지 않습니다.
     *
     * <p>신호 — 데모 열람 토큰(ADR 0013), Secure 쿠키(HTTPS 앞단), 앞단 헤더 신뢰(AWS는
     * {@code SERVER_FORWARDHEADERSSTRATEGY=native}), 이 맥의 http 주소가 아닌 화면 주소(FRONTEND_ORIGIN).
     *
     * @throws IllegalStateException 신호가 있을 때 — 문구에는 설정 이름만 담고 값(토큰 등)은 담지 않습니다
     */
    static void check(AppProperties properties, String forwardHeadersStrategy) {
        if (!properties.isLocalNoLogin()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        String token = properties.getDemoViewerToken();
        if (token != null && !token.isBlank()) {
            problems.add("데모 열람 토큰(DASHBOARD_DEMO_VIEWER_TOKEN)이 있음");
        }
        if (properties.isCookieSecure()) {
            problems.add("세션 쿠키가 HTTPS 전용(COOKIE_SECURE=true)");
        }
        String strategy = forwardHeadersStrategy == null ? "" : forwardHeadersStrategy.strip();
        if (!strategy.isEmpty() && !"none".equalsIgnoreCase(strategy)) {
            problems.add("앞단 프록시 헤더를 믿음(server.forward-headers-strategy가 none이 아님)");
        }
        String origins = properties.getFrontendOrigin() == null ? "" : properties.getFrontendOrigin();
        for (String origin : origins.split(",")) {
            if (!origin.isBlank() && !isLoopbackHttpOrigin(origin.strip())) {
                problems.add("화면 주소(FRONTEND_ORIGIN)에 이 맥의 http 주소(localhost·127.0.0.1·[::1])가 아닌 값이 있음");
                break;
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("로컬 무로그인(DASHBOARD_LOCAL_NO_LOGIN)이 켜져 있는데 웹 배포 설정이 있어 기동하지 않습니다 — "
                    + String.join(", ", problems) + ". 이 맥에서만 쓰면 그 설정을 지우고, 웹으로 열려면 "
                    + "LOCAL_NO_LOGIN=false로 로그인 모드를 쓰세요(ADR 0016).");
        }
    }

    /** {@code http://<이 맥>[:포트]} 꼴의 origin인가(경로·사용자 정보 없음, 끝의 / 하나는 허용). */
    static boolean isLoopbackHttpOrigin(String origin) {
        try {
            URI uri = new URI(origin);
            String path = uri.getRawPath();
            return "http".equals(uri.getScheme()) && uri.getRawUserInfo() == null
                    && uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    && (path == null || path.isEmpty() || "/".equals(path))
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * {@code server.forward-headers-strategy} — 스프링 부트가 웹 서버 설정을 읽는 것과 같은 방식(Binder)으로 읽어,
     * 환경 변수 {@code SERVER_FORWARDHEADERSSTRATEGY}처럼 이름을 느슨하게 쓴 값도 잡습니다. 값이 없으면 부트처럼 클라우드
     * 플랫폼(Kubernetes·Cloud Foundry 등)을 보고 스스로 native를 켜는 경우까지 따릅니다 — 그때는 앞단 헤더가 Host를 바꿔
     * 이 맥 판정을 흉내 낼 수 있습니다. 둘 다 아니면 none.
     */
    static String forwardHeadersStrategy(Environment environment) {
        return Binder.get(environment).bind("server.forward-headers-strategy", String.class).orElseGet(() -> {
            CloudPlatform platform = CloudPlatform.getActive(environment);
            return platform != null && platform.isUsingForwardHeaders() ? "native" : "none";
        });
    }
}

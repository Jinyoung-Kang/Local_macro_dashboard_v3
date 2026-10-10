package com.macrodash.config;

import com.macrodash.feature.auth.AccessPolicy;
import com.macrodash.feature.auth.AuthService;
import com.macrodash.feature.auth.DemoViewer;
import com.macrodash.support.CurrentUser;
import com.macrodash.feature.auth.AuthController;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * CORS와 인증 필터.
 *
 * <p>인증은 단순합니다 — 로그인·헬스체크를 제외한 모든 {@code /api/**} 요청에
 * 유효한 세션 쿠키가 있어야 하고, 경로마다 필요한 역할·등급을 {@link AccessPolicy}가 정합니다(ADR 0010).
 * 예외는 AWS 공개 데모의 데모 열람 하나입니다(기본은 꺼짐, ADR 0013) — {@link DemoViewer}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 쿠키를 주고받아야 하므로 allowCredentials가 필요하고,
        // 그 경우 오리진에 와일드카드를 쓸 수 없습니다.
        registry.addMapping("/api/**")
                // 패턴에는 정확한 origin도 들어갑니다. 화면 포트의 origin은 호스트와 무관하게
                // 허용합니다 — 휴대폰이 http://192.168.x.x:3000으로 열면 API도 같은 호스트의
                // 백엔드 포트를 부르므로(lib/api.ts) origin이 LAN IP가 됩니다. 쿠키는 SameSite=Strict라
                // 다른 사이트의 요청에는 애초에 실리지 않아, 패턴을 넓혀도 인증이 새지 않습니다.
                .allowedOriginPatterns(allowedOriginPatterns(properties).toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /** FRONTEND_ORIGIN의 명시 목록 + 화면 포트의 모든 호스트(http://*:포트). */
    static List<String> allowedOriginPatterns(AppProperties properties) {
        List<String> patterns = new java.util.ArrayList<>(Arrays.stream(properties.getFrontendOrigin().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList());
        String anyHost = "http://*:" + properties.getFrontendPort();
        if (!patterns.contains(anyHost)) {
            patterns.add(anyHost);
        }
        return patterns;
    }

    @Bean
    public FilterRegistrationBean<SessionFilter> sessionFilter(AuthService authService) {
        FilterRegistrationBean<SessionFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SessionFilter(authService, allowedOriginPatterns(properties)));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }

    /**
     * 세션 쿠키 검사 + 보안 응답 헤더.
     *
     * <p>공개 경로는 <b>정확히 일치</b>할 때만 통과시킵니다. 예전에는 접두사로
     * 비교해 {@code /api/healthx}, {@code /api/health/../macro/overview} 같은
     * 요청이 인증 검사를 건너뛰었습니다(지금은 해당 매핑이 없어 404로 끝났지만,
     * 비슷한 이름의 엔드포인트가 생기는 순간 인증 없이 열립니다).
     *
     * <p>경로는 컨테이너가 디코딩·정규화한 {@code servletPath}로 봅니다.
     * {@code getRequestURI()}는 날것이라 {@code ..}·{@code %2e}가 그대로 남습니다.
     */
    public static class SessionFilter extends OncePerRequestFilter {

        /** 로그인 없이 받는 경로 — {@link AccessPolicy}의 공개 경로와 같아야 합니다(SessionFilterTest가 대조). */
        static final Set<String> PUBLIC_PATHS = Set.of(
                "/api/auth/login", "/api/auth/session", "/api/health", "/api/auth/signup", "/api/auth/verify",
                "/api/auth/reset/request", "/api/auth/reset/confirm");

        /**
         * 로그인 본문 상한(바이트). 로그인은 인증 없이 받는 유일한 JSON 입력이라, 크기 제한이
         * 없으면 LAN의 아무나 수 MB짜리 {"password": "AAAA…"}를 보내 힙을 압박할 수 있습니다
         * (Tomcat의 maxPostSize는 JSON에 적용되지 않습니다). 이메일·비밀번호 한 줄에는 4KB면 충분합니다.
         */
        static final long MAX_LOGIN_BODY_BYTES = 4096;

        private final AuthService authService;
        /** 상태를 바꾸는 요청(POST·PUT·DELETE)의 Origin이 이 목록(CORS와 같음)에 맞아야 합니다. */
        private final org.springframework.web.cors.CorsConfiguration origins = new org.springframework.web.cors.CorsConfiguration();

        public SessionFilter(AuthService authService, List<String> allowedOriginPatterns) {
            this.authService = authService;
            this.origins.setAllowedOriginPatterns(allowedOriginPatterns);
        }

        /**
         * 다른 출처에서 온 상태 변경 요청을 막습니다. 세션 쿠키는 SameSite=Strict라 다른 사이트의 요청에 실리지 않지만,
         * 로그인처럼 쿠키 없이 받는 요청과 하위 도메인 배포까지 고려해 한 겹 더 둡니다. Origin이 없는 요청(스크립트·curl)은
         * 브라우저가 아니므로 통과시킵니다 — 브라우저는 다른 출처로 POST할 때 Origin을 반드시 붙입니다.
         */
        boolean foreignOrigin(HttpServletRequest request) {
            String method = request.getMethod();
            if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                    || "DELETE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))) {
                return false;
            }
            String origin = request.getHeader("Origin");
            return origin != null && origins.checkOrigin(origin) == null;
        }

        /**
         * 데모 열람이 GET 밖에서 부를 수 있는 단 하나 — AI 리포트 생성(ADR 0014). DB에 쓰지 않고, 사용량은 방문자 전체가
         * 함께 쓰는 메모리의 하루 한도로 셉니다({@code feature.auth.UsageService}). 등급 검사는 접근 정책 표 그대로(유료).
         */
        static final String DEMO_WRITABLE_PATH = "/api/ai/report";

        /**
         * 데모 열람이 할 수 없는 요청인가(ADR 0013) — 데모 열람은 읽기 전용이라 GET·OPTIONS(CORS 사전 요청) 밖은 경로와
         * 상관없이(로그인·가입 같은 공개 경로 포함) 403입니다. 예외는 {@code POST} {@link #DEMO_WRITABLE_PATH} 하나입니다
         * (ADR 0014). 유효한 세션 쿠키가 함께 오면 세션이 이기므로 막지 않습니다.
         * 헤더가 없거나 틀리면(기능이 꺼져 있으면 늘) 아무것도 하지 않습니다 — 평소처럼 401·정책 검사로 갑니다.
         *
         * <p>HEAD도 막습니다 — 화면은 HEAD를 쓰지 않고, 앞단(Caddy)도 같은 규칙입니다. HEAD가 접근 정책 표를 건너뛰던
         * 결함(QA-017)은 {@link AccessPolicy#required}가 HEAD를 GET으로 보게 고쳤고, 이 차단은 그 위의 한 겹입니다.
         */
        boolean demoForbidden(HttpServletRequest request) {
            String method = request.getMethod();
            if ("GET".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
                return false;
            }
            if ("POST".equalsIgnoreCase(method) && DEMO_WRITABLE_PATH.equals(pathOf(request))) {
                return false;
            }
            String header = request.getHeader(DemoViewer.HEADER);
            if (header == null || !authService.isDemoViewer(header)) {
                return false;
            }
            return authService.authenticate(AuthController.readToken(request)).isEmpty();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            addSecurityHeaders(response);

            if (foreignOrigin(request)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"error\":\"bad_origin\",\"message\":\"다른 출처에서 온 요청은 받지 않습니다.\"}");
                return;
            }

            if (demoForbidden(request)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"error\":\"demo_read_only\",\"message\":\"데모에서는 조회만 가능합니다.\"}");
                return;
            }

            if (isLoginPost(request)) {
                // 본문을 상한까지만 읽습니다. Content-Length가 없는(chunked) 요청도 같은 상한을 받습니다.
                byte[] body = request.getContentLengthLong() > MAX_LOGIN_BODY_BYTES
                        ? null
                        : request.getInputStream().readNBytes((int) MAX_LOGIN_BODY_BYTES + 1);
                if (body == null || body.length > MAX_LOGIN_BODY_BYTES) {
                    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"error\":\"too_large\",\"message\":\"요청이 너무 큽니다.\"}");
                    return;
                }
                chain.doFilter(new CappedBodyRequest(request, body), response);
                return;
            }

            if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublic(request)) {
                chain.doFilter(request, response);
                return;
            }

            // 세션이 먼저, 없으면 데모 열람 헤더(ADR 0013 — 기능이 꺼져 있거나 값이 틀리면 헤더는 없는 것과 같음).
            Optional<CurrentUser> user = authService.resolve(AuthController.readToken(request),
                    request.getHeader(DemoViewer.HEADER));
            if (user.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"error\":\"unauthorized\",\"message\":\"로그인이 필요합니다.\"}");
                return;
            }
            AccessPolicy.Access required = AccessPolicy.required(request.getMethod(), pathOf(request));
            if (!AccessPolicy.allows(required, user.get().role(), user.get().tier())) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                String message = required == AccessPolicy.Access.OWNER
                        ? "운영자만 쓸 수 있는 기능입니다." : "유료 등급에서 쓸 수 있는 기능입니다.";
                response.getWriter().write("{\"error\":\"forbidden\",\"required\":\"" + required.name()
                        + "\",\"message\":\"" + message + "\"}");
                return;
            }
            request.setAttribute(CurrentUser.ATTRIBUTE, user.get());
            chain.doFilter(request, response);
        }

        /**
         * API 응답에 붙이는 헤더.
         *
         * <ul>
         *   <li>nosniff — JSON을 스크립트·HTML로 해석하지 못하게 합니다.</li>
         *   <li>no-store — 로그인해야 보이는 데이터가 브라우저·프록시 캐시에 남지 않게 합니다.</li>
         *   <li>DENY / frame-ancestors 'none' — API 응답을 다른 페이지에 끼워 넣지 못하게 합니다.</li>
         * </ul>
         */
        static void addSecurityHeaders(HttpServletResponse response) {
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            response.setHeader("Referrer-Policy", "no-referrer");
        }

        static boolean isPublic(HttpServletRequest request) {
            return PUBLIC_PATHS.contains(pathOf(request));
        }

        /** 본문 상한을 거는 요청 — 로그인 없이 받는 POST(로그인·가입·인증·재설정) 전부. */
        static boolean isLoginPost(HttpServletRequest request) {
            return "POST".equalsIgnoreCase(request.getMethod()) && PUBLIC_PATHS.contains(pathOf(request))
                    && !"/api/auth/session".equals(pathOf(request)) && !"/api/health".equals(pathOf(request));
        }

        /** 상한 안에서 미리 읽어 둔 본문을 컨트롤러에 그대로 넘기는 요청. */
        static final class CappedBodyRequest extends HttpServletRequestWrapper {
            private final byte[] body;

            CappedBodyRequest(HttpServletRequest request, byte[] body) {
                super(request);
                this.body = body;
            }

            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream in = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public boolean isFinished() { return in.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { }
                    @Override public int read() { return in.read(); }
                };
            }

            @Override
            public BufferedReader getReader() {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                return new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }

            @Override public int getContentLength() { return body.length; }
            @Override public long getContentLengthLong() { return body.length; }
        }

        private static String pathOf(HttpServletRequest request) {
            String path = request.getServletPath();
            if (request.getPathInfo() != null) {
                path = path + request.getPathInfo();
            }
            return path;
        }
    }
}

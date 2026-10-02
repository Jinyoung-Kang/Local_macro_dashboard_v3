package com.macrodash.config;

import com.macrodash.feature.auth.AuthService;
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
import java.util.Set;

/**
 * CORS와 인증 필터.
 *
 * <p>인증은 단순합니다 — 로그인·헬스체크를 제외한 모든 {@code /api/**} 요청에
 * 유효한 세션 쿠키가 있어야 합니다. 사용자 계정 체계가 없는 1인용 대시보드라
 * 구버전의 "비밀번호 한 개" 모델을 그대로 유지합니다.
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
        registration.setFilter(new SessionFilter(authService));
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

        static final Set<String> PUBLIC_PATHS = Set.of(
                "/api/auth/login", "/api/auth/session", "/api/health");

        /**
         * 로그인 본문 상한(바이트). 로그인은 인증 없이 받는 유일한 JSON 입력이라, 크기 제한이
         * 없으면 LAN의 아무나 수 MB짜리 {"password": "AAAA…"}를 보내 힙을 압박할 수 있습니다
         * (Tomcat의 maxPostSize는 JSON에 적용되지 않습니다). 비밀번호 한 줄에는 4KB면 충분합니다.
         */
        static final long MAX_LOGIN_BODY_BYTES = 4096;

        private final AuthService authService;

        public SessionFilter(AuthService authService) {
            this.authService = authService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            addSecurityHeaders(response);

            if (isLoginPost(request)) {
                // 본문을 상한까지만 읽습니다. Content-Length가 없는(chunked) 요청도 같은 상한을 받습니다.
                byte[] body = request.getContentLengthLong() > MAX_LOGIN_BODY_BYTES
                        ? null
                        : request.getInputStream().readNBytes((int) MAX_LOGIN_BODY_BYTES + 1);
                if (body == null || body.length > MAX_LOGIN_BODY_BYTES) {
                    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"error\":\"too_large\",\"message\":\"로그인 요청이 너무 큽니다.\"}");
                    return;
                }
                chain.doFilter(new CappedBodyRequest(request, body), response);
                return;
            }

            if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublic(request)) {
                chain.doFilter(request, response);
                return;
            }

            if (authService.isValid(AuthController.readToken(request))) {
                chain.doFilter(request, response);
                return;
            }

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"error\":\"unauthorized\",\"message\":\"로그인이 필요합니다.\"}");
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

        static boolean isLoginPost(HttpServletRequest request) {
            return "POST".equalsIgnoreCase(request.getMethod()) && "/api/auth/login".equals(pathOf(request));
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

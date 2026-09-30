package com.macrodash.config;

import com.macrodash.web.ApiExceptionHandler;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.WebRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 컨트롤러 밖에서 난 오류(서블릿 컨테이너 → {@code /error})도 API와 같은 모양으로 답합니다.
 *
 * <p>컨트롤러 안의 예외는 {@link ApiExceptionHandler}가 처리합니다. 여기는 그 밖 —
 * 필터에서 난 예외 등 — 을 위한 것입니다. 기본 오류 본문의 timestamp·path·예외 문구를
 * 내보내지 않고 {@code {error, message}}만 남깁니다.
 */
@Configuration
public class ErrorResponseConfig {

    @Bean
    public ErrorAttributes errorAttributes() {
        return new DefaultErrorAttributes() {
            @Override
            public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
                Object raw = super.getErrorAttributes(request, ErrorAttributeOptions.defaults()).get("status");
                int status = raw instanceof Integer value ? value : 500;
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("error", ApiExceptionHandler.errorCode(status));
                out.put("message", ApiExceptionHandler.messageFor(status));
                return out;
            }
        };
    }
}

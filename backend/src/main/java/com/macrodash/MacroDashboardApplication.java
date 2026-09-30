package com.macrodash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 매크로 대시보드 백엔드.
 *
 * <p>역할 분담
 * <ul>
 *   <li>수집기(Python/FastAPI): 외부 소스 수집·적재, 키가 필요한 실시간 조회</li>
 *   <li>백엔드(여기): 저장본 읽기 · 분석 · 인증 · 화면용 API</li>
 *   <li>프런트(Next.js): 표시</li>
 * </ul>
 *
 * <p>구버전 Streamlit 앱에서 화면 코드에 섞여 있던 계산(수익률 매트릭스,
 * 13F 분기 대비 액션, COT 요약, 교차 검증 판정)을 이 계층으로 옮겼습니다.
 * 같은 원본에서 화면과 AI 리포트가 서로 다른 숫자를 말하는 일을 막기 위해서입니다.
 */
@SpringBootApplication
public class MacroDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(MacroDashboardApplication.class, args);
    }
}

package com.macrodash.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 백엔드 정기 작업(ADR 0008) — 국면 판정 기록 등.
 *
 * <p>{@code dashboard.scheduler.enabled=false}면 끕니다. 통합 테스트는 꺼 둡니다 — 테스트 도중 정기 작업이
 * 테스트 DB에 쓰면 결과가 실행 시각에 따라 달라집니다. 작업마다 같은 일을 두 번 해도 결과가 한 번이도록
 * (DB 유일 제약) 만들어, 재시작이 겹쳐도 중복 기록이 생기지 않습니다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "dashboard.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}

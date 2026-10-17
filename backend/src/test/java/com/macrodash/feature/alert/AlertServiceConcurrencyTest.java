package com.macrodash.feature.alert;

import com.macrodash.store.AlertStore;
import com.macrodash.support.CurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-092 알림 규칙 상한(규칙 수·사용자 20종목·전체 60종목)은 동시 요청에도 넘지 않는다.
 *
 * <p>상한 검사(세기)와 저장이 따로 도는 문장이라, 동시에 들어온 요청이 모두 저장 전 값을 보고 통과했습니다. 전체 60종목을
 * 넘으면 수집기가 코드 순으로 앞 60개만 받아, 먼저 규칙을 건 다른 사용자의 종목이 조용히 수집에서 빠집니다(QA-041의 증상).
 *
 * <p>경합 창을 결정적으로 열기 위해, 메모리 저장소의 저장(insert·켜기)은 모든 요청이 검사를 마칠 때까지 장벽에서 기다립니다.
 * 검사부터 저장까지가 한 줄로 서면 첫 요청만 장벽에서 기다리다(시간 초과로 장벽이 깨짐) 저장하고, 나머지는 바뀐 값을 봅니다.
 */
class AlertServiceConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("QA-092 한 유료 회원이 서로 다른 종목 30개를 동시에 만들어도 규칙·관심 종목은 20개까지")
    void concurrentCreatesRespectPerUserCaps() throws Exception {
        CurrentUser pro = new CurrentUser(7, "p@x", "MEMBER", "PRO", null);
        RacingStore store = new RacingStore(30);
        AlertService service = new AlertService(store, MAPPER);

        List<Integer> statuses = concurrently(30, i -> service.create(pro, "FOREIGN_STREAK", stock("%06d".formatted(10 + i)))
                .status());

        assertThat(store.countRules(pro.id())).as("규칙 수 상한(유료 20)").isEqualTo(AlertService.PRO_RULES);
        assertThat(store.countStockCodes(pro.id())).as("사용자 관심 종목 상한(20)").isEqualTo(AlertService.STOCKS_PER_USER);
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(20);
    }

    @Test
    @DisplayName("QA-092 전체 59종목일 때 두 회원이 서로 다른 새 종목을 동시에 만들거나 켜도 전체 60종목을 넘지 않는다")
    void concurrentCreatesAndEnablesRespectTotalCap() throws Exception {
        RacingStore store = new RacingStore(2);
        for (int i = 0; i < 59; i++) {                    // 다른 회원 셋이 이미 건 59종목
            store.seed(100 + i % 3, stock("%06d".formatted(900000 + i)), true);
        }
        AlertService service = new AlertService(store, MAPPER);
        CurrentUser a = new CurrentUser(1, "a@x", "MEMBER", "PRO", null);
        CurrentUser b = new CurrentUser(2, "b@x", "MEMBER", "PRO", null);

        List<Integer> created = concurrently(2, i -> service.create(i == 0 ? a : b, "FOREIGN_STREAK",
                stock(i == 0 ? "000001" : "000002")).status());

        assertThat(store.countStockCodes(null)).as("만들기 — 전체 60종목").isEqualTo(AlertService.STOCKS_TOTAL);
        assertThat(created).containsExactlyInAnyOrder(200, 400);

        // 켜기 경로도 같습니다 — 59종목으로 되돌린 뒤 꺼 둔 서로 다른 종목 규칙 둘을 동시에 켭니다.
        RacingStore enabling = new RacingStore(2);
        for (int i = 0; i < 59; i++) {
            enabling.seed(100 + i % 3, stock("%06d".formatted(900000 + i)), true);
        }
        long ruleA = enabling.seed(a.id(), stock("000001"), false);
        long ruleB = enabling.seed(b.id(), stock("000002"), false);
        AlertService enablingService = new AlertService(enabling, MAPPER);

        List<Integer> enabled = concurrently(2, i -> enablingService.setEnabled(i == 0 ? a : b, i == 0 ? ruleA : ruleB, true)
                .status());

        assertThat(enabling.countStockCodes(null)).as("켜기 — 전체 60종목").isEqualTo(AlertService.STOCKS_TOTAL);
        assertThat(enabled).containsExactlyInAnyOrder(200, 400);
    }

    private static JsonNode stock(String code) {
        return MAPPER.createObjectNode().put("target", "STOCK").put("code", code).put("n", 5);
    }

    private interface Call {
        int run(int index) throws Exception;
    }

    /** {@code n}개 요청을 한꺼번에 출발시켜 상태 코드를 모읍니다. */
    private static List<Integer> concurrently(int n, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                Callable<Integer> task = () -> {
                    start.await();
                    return call.run(index);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(20, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 메모리 알림 규칙 저장소 — 상한 검사에 쓰는 조회를 실제 SQL과 같은 뜻으로 흉내 냅니다(켜진 종목 규칙의 서로 다른 코드 수 등).
     * 저장(insert·켜기)은 {@code parties}개 요청이 모두 검사를 마칠 때까지 장벽에서 기다립니다(최대 1초).
     */
    static final class RacingStore extends AlertStore {

        private final List<Rule> saved = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong();
        private final CyclicBarrier barrier;

        RacingStore(int parties) {
            super(null, null, null);
            this.barrier = new CyclicBarrier(parties);
        }

        long seed(long userId, JsonNode params, boolean enabled) {
            synchronized (saved) {
                long id = ids.incrementAndGet();
                saved.add(new Rule(id, userId, "FOREIGN_STREAK", params, enabled, Instant.now()));
                return id;
            }
        }

        private void waitForOthers() {
            try {
                barrier.await(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 검사가 한 줄로 서 있으면 혼자 기다리다 장벽이 깨집니다 — 그대로 저장합니다.
            }
        }

        @Override
        public List<Rule> rules(long userId) {
            synchronized (saved) {
                return saved.stream().filter(rule -> rule.userId() == userId).toList();
            }
        }

        @Override
        public int countRules(long userId) {
            return rules(userId).size();
        }

        @Override
        public int countStockCodes(Long userId) {
            synchronized (saved) {
                return (int) saved.stream()
                        .filter(rule -> rule.enabled() && "FOREIGN_STREAK".equals(rule.kind())
                                && "STOCK".equals(rule.params().path("target").asText())
                                && (userId == null || rule.userId() == userId))
                        .map(rule -> rule.params().path("code").asText()).distinct().count();
            }
        }

        @Override
        public boolean hasStockCode(String code) {
            synchronized (saved) {
                return saved.stream().anyMatch(rule -> rule.enabled() && "FOREIGN_STREAK".equals(rule.kind())
                        && "STOCK".equals(rule.params().path("target").asText())
                        && code.equals(rule.params().path("code").asText()));
            }
        }

        @Override
        public long insertRule(long userId, String kind, JsonNode params) {
            waitForOthers();
            synchronized (saved) {
                long id = ids.incrementAndGet();
                saved.add(new Rule(id, userId, kind, params, true, Instant.now()));
                return id;
            }
        }

        @Override
        public int setEnabled(long ruleId, long userId, boolean enabled) {
            if (enabled) {
                waitForOthers();
            }
            synchronized (saved) {
                for (int i = 0; i < saved.size(); i++) {
                    Rule rule = saved.get(i);
                    if (rule.id() == ruleId && rule.userId() == userId) {
                        saved.set(i, new Rule(rule.id(), rule.userId(), rule.kind(), rule.params(), enabled,
                                rule.createdAt()));
                        return 1;
                    }
                }
                return 0;
            }
        }
    }
}

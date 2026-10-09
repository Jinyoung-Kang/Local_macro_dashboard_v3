package com.macrodash.feature.auth;

import com.macrodash.Kst;
import com.macrodash.store.UsageStore;
import com.macrodash.support.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 사용자별 하루 사용량 한도(명세 B7) — 지금은 AI 리포트 하나. 유료 API 비용이 사용자 수만큼 늘지 않게 막습니다.
 *
 * <p>운영자는 한도가 없고(세기는 함), 유료 회원은 하루(KST) {@code dashboard.ai.daily-limit}건, 무료 회원은 쓸 수
 * 없습니다(접근 정책이 이미 막지만 여기서도 0건으로 봅니다).
 */
@Service
public class UsageService {

    public static final String AI_REPORT = "ai.report";

    /** 이번 요청의 결과. {@code limit}이 null이면 한도 없음. */
    public record Quota(boolean allowed, int used, Integer limit) {
    }

    private final UsageStore usage;
    private final int aiDailyLimit;

    public UsageService(UsageStore usage, @Value("${dashboard.ai.daily-limit:20}") int aiDailyLimit) {
        this.usage = usage;
        this.aiDailyLimit = Math.max(0, aiDailyLimit);
    }

    /** 한 건을 씁니다. 한도를 넘으면 세지 않고 allowed=false. */
    public Quota consume(CurrentUser user, String kind) {
        LocalDate today = Kst.today();
        if (user.isOwner()) {
            usage.tryIncrement(user.id(), today, kind, Integer.MAX_VALUE);
            return new Quota(true, usage.count(user.id(), today, kind), null);
        }
        int limit = user.isPro() ? aiDailyLimit : 0;
        boolean allowed = limit > 0 && usage.tryIncrement(user.id(), today, kind, limit);
        return new Quota(allowed, usage.count(user.id(), today, kind), limit);
    }

    /** 쓰지 못한 호출(엔진이 모두 실패)은 되돌립니다. */
    public void refund(CurrentUser user, String kind) {
        usage.decrement(user.id(), Kst.today(), kind);
    }
}

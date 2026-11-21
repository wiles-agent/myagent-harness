package dev.wiles.myagent.server;

import dev.wiles.myagent.tool.safety.ApprovalScope;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import dev.wiles.myagent.tool.safety.AutoReviewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AutoModeTest {

    private final AtomicInteger made = new AtomicInteger();
    private final AutoMode mode = new AutoMode(() -> {
        int n = made.incrementAndGet();
        // 捕获 n：不捕获东西的 lambda JVM 会缓存成同一个实例，「换了一个」就验不出来
        return (AutoReviewer) request -> n > 0 ? ApprovalVerdict.yes() : ApprovalVerdict.no();
    });

    @AfterEach
    void clear() {
        ApprovalScope.clear();
    }

    @Test
    @DisplayName("没开 auto：这一轮的线程上没有审查，照常问人")
    void offMeansNoReviewer() {
        mode.enterTurn();
        assertNull(ApprovalScope.autoReviewer());
        assertEquals(0, made.get(), "没开就别造审查器（造它要连模型）");
    }

    @Test
    @DisplayName("开了 auto：线程上挂着审查；一段会话里是同一个——连拒几次的账要跨轮记")
    void onAttachesOneReviewerPerSession() {
        mode.set(true);
        mode.enterTurn();
        AutoReviewer first = ApprovalScope.autoReviewer();
        assertNotNull(first);
        mode.enterTurn();
        assertSame(first, ApprovalScope.autoReviewer());
        assertEquals(1, made.get());
    }

    @Test
    @DisplayName("换会话（reset）换一个审查器；关掉 auto 下一轮就不挂了")
    void resetAndTurnOff() {
        mode.set(true);
        mode.enterTurn();
        AutoReviewer first = ApprovalScope.autoReviewer();
        mode.reset();
        mode.enterTurn();
        assertNotSame(first, ApprovalScope.autoReviewer());

        mode.set(false);
        mode.enterTurn();
        assertNull(ApprovalScope.autoReviewer());
        assertFalse(mode.on());
    }
}

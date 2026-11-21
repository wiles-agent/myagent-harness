package dev.wiles.myagent.server;

import dev.wiles.myagent.tool.safety.ApprovalScope;
import dev.wiles.myagent.tool.safety.AutoReviewer;

import java.util.function.Supplier;

/**
 * 主会话的 auto 模式（照 Claude Code）：开着时，要问人的操作交给自动审查按用户的意图判，不弹框。
 *
 * <p>开关在界面上切（{@code set_mode auto}），生效在<b>下一轮</b>：审查器挂在跑这一轮的线程上
 * （{@link ApprovalScope}，工具调用新开的线程继承它），一轮中途切模式，这一轮照旧按开始时的来。
 *
 * <p>审查器一段会话一个：它记着连拒几次、累计拒了几次，到线就交还给人——这笔账要跨轮记，
 * 每轮新造一个的话永远到不了线。换会话（reset）才换新的。
 */
final class AutoMode {

    private final Supplier<AutoReviewer> factory;
    private volatile boolean on;
    private volatile AutoReviewer reviewer;

    AutoMode(Supplier<AutoReviewer> factory) {
        this.factory = factory;
    }

    boolean on() {
        return on;
    }

    void set(boolean on) {
        this.on = on;
    }

    /** 换了一段会话：之前的拒绝记录不该带过来。 */
    void reset() {
        reviewer = null;
    }

    /** 一轮开始前，在跑这一轮的线程上调。没开就清掉，免得复用的线程上留着上一轮的。 */
    synchronized void enterTurn() {
        if (!on) {
            ApprovalScope.clear();
            return;
        }
        if (reviewer == null) {
            reviewer = factory.get();
        }
        ApprovalScope.set(null, false, reviewer);
    }
}

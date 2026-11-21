package dev.wiles.myagent.tool.safety;

/**
 * 「这一刻是谁在调工具」——跟着线程走的审批来源。
 *
 * <h3>为什么不能是一个全局字段</h3>
 * 原来 {@code RemoteApproval} 上有一个 volatile 的 origin，由调度层在任务起止时改。
 * 那时靠「写任务独占运行」保证同一刻只有一个执行体会要审批，全局字段想错都错不了。
 * 计划调度下线之后没人再改它，而现在主 agent 和几个 worker 是<b>同时</b>在跑的：
 * worker 在后台要跑 {@code rm -rf build}，弹出来的框什么都不说，用户只会以为是主 agent 在问——
 * 想不清楚是谁要干的时候，人的默认动作是点同意。
 *
 * <h3>为什么是 InheritableThreadLocal</h3>
 * 子 agent 的循环跑在它自己的线程上（{@code SubAgents} 派出时新开的），而每次工具调用
 * 又是从循环线程里<b>新开</b>一个线程去跑（{@code Agent} 要能叫停它）——新线程继承父线程的值，
 * 来源就一路带到工具里的 {@code approval.decide(...)}，中间哪一层都不用改签名。
 * 主 agent 的线程从来不设，所以它的调用来源是空的，和以前一样。
 *
 * <p>照 Claude Code：后台子 agent 的权限请求出现在主会话里，<b>标明是哪个子 agent</b>。
 */
public final class ApprovalScope {

    private static final InheritableThreadLocal<ApprovalOrigin> CURRENT = new InheritableThreadLocal<>();

    private ApprovalScope() {
    }

    /** 当前线程（及它之后新开的线程）发起的审批算在 {@code who} 头上。 */
    public static void set(ApprovalOrigin who) {
        if (who == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(who);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 当前线程的来源；没设过是 null。 */
    public static ApprovalOrigin current() {
        return CURRENT.get();
    }
}

package dev.wiles.myagent.tool.safety;

/**
 * 规则层之后、问人之前的那一道：这条线程在 auto 下，就交给自动审查；在 dontAsk 下，直接拒。
 *
 * <pre>
 *   PolicyGate（规则；auto 下丢掉宽泛的放行规则）
 *     └─ AuditLog.audited（留底）
 *          └─ AutoReviewGate（auto → 审查；dontAsk → 拒）
 *               └─ RemoteApproval（问人） / 无头运行时的「全都批」
 * </pre>
 *
 * <h3>审查交还给人时，下一层不一定是人</h3>
 * 服务端的下一层是 {@code RemoteApproval}，交给它就是问人（照 Claude Code）。无头运行的下一层是
 * {@code approveAll}——交给它等于连拒几次之后反而全放行。所以无头时 {@code escalateToNext=false}：交还 = 拒。
 * dontAsk 也放在这一层判，同一个原因：以前它只在 {@code RemoteApproval} 里，无头运行时根本不生效。
 */
public final class AutoReviewGate implements ApprovalPolicy {

    private final ApprovalPolicy next;
    private final boolean escalateToNext;

    /**
     * @param next           下一层
     * @param escalateToNext 下一层是不是真的在问人。不是（无头）就传 false
     */
    public AutoReviewGate(ApprovalPolicy next, boolean escalateToNext) {
        this.next = next;
        this.escalateToNext = escalateToNext;
    }

    /** auto 下被拒之后通知谁（照 Claude Code 的 PermissionDenied hook）；它回 true 表示「可以重试」。 */
    private volatile java.util.function.BiPredicate<ApprovalRequest, String> onDenied = (request, reason) -> false;

    public AutoReviewGate onDenied(java.util.function.BiPredicate<ApprovalRequest, String> hook) {
        this.onDenied = hook == null ? (request, reason) -> false : hook;
        return this;
    }

    /** 审查给了结论的拒绝：通知 hook，它说能重试就告诉模型。 */
    private ApprovalVerdict denied(ApprovalRequest request, ApprovalVerdict verdict) {
        boolean retry;
        try {
            retry = onDenied.test(request, verdict.note());
        } catch (RuntimeException e) {
            retry = false;
        }
        return retry ? ApprovalVerdict.no(verdict.note() + "（PermissionDenied hook 说这一下可以重试）") : verdict;
    }

    @Override
    public ApprovalVerdict decide(ApprovalRequest request) {
        if (ApprovalScope.humanRequired()) {
            // 撞上 ask 规则：一定问人，审查不接；dontAsk 和无头都没人可问，只能拒
            if (ApprovalScope.dontAsk()) {
                return ApprovalVerdict.no("这个操作撞上了 ask 规则、必须问用户，但这个子 agent 是 dontAsk 模式，不做。"
                        + "需要的话在答复里说明，交给主 agent");
            }
            if (!escalateToNext) {
                return ApprovalVerdict.no("这个操作撞上了 ask 规则、必须问用户，但这里没有人可问（无头运行），不做");
            }
            return next.decide(request);
        }
        AutoReviewer reviewer = ApprovalScope.autoReviewer();
        if (reviewer != null) {
            ApprovalVerdict verdict = reviewer.review(request);
            if (verdict != null) {
                return verdict.approved() || verdict.cancelled() ? verdict : denied(request, verdict);
            }
            if (!escalateToNext) {
                ApprovalVerdict no = ApprovalVerdict.no("自动审查已经连续拒绝多次、交还给人，但这里没有人可问（无头运行），"
                        + "这类操作不再执行。在最后的答复里说明需要做什么、为什么，交给用户决定");
                try {
                    onDenied.test(request, no.note());   // 没有审查结论：照样通知，但不认 retry（照 Claude Code）
                } catch (RuntimeException ignored) {
                    // 通知失败不影响拒绝
                }
                return no;
            }
            return next.decide(request);
        }
        if (ApprovalScope.dontAsk()) {
            return ApprovalVerdict.no("这个子 agent 是 dontAsk 模式：要问用户的操作一律不做。"
                    + "需要的话在最后的答复里说清楚要做什么、为什么，交给主 agent");
        }
        return next.decide(request);
    }
}

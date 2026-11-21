package dev.wiles.myagent.tool.safety;

/**
 * 危险动作要不要放行，由它说了算。
 *
 * <p>之所以单独抽一个接口，是因为「问谁」在不同场景下不一样：
 * 无头模式发给界面（{@code RemoteApproval}），自动化脚本里没人可问（一律拒绝），
 * 测试里则要一律放行。
 *
 * <h3>实现是叠起来用的</h3>
 * <pre>
 *   PolicyGate（先算规则，算不出来才往下问）
 *     └─ AuditLog.audited（每个决策都留底）
 *          └─ RemoteApproval（发给界面，阻塞等人回答）
 * </pre>
 * 每一层都只管一件事，也因此加一层不用动其它层。
 *
 * <h3>为什么参数和返回值都变成了记录</h3>
 * 原签名是 {@code boolean approve(action, detail, reason)}。三个字符串能把问题问出来，
 * 但接不住答案里真正有用的部分——用户拒绝时想说的那句话，和同意时愿意一次性授权的范围。
 * 详见 {@link ApprovalRequest} 和 {@link ApprovalVerdict}。
 */
@FunctionalInterface
public interface ApprovalPolicy {

    ApprovalVerdict decide(ApprovalRequest request);

    /**
     * 只看 deny 规则：有一条拦下就返回拒绝，否则 null。沙箱里自动放行的命令用（照 Claude Code：
     * 沙箱里的命令不用问，但用户明说不许的照样拦）。只有规则层（{@link PolicyGate}）知道规则，别的实现不管。
     */
    default ApprovalVerdict denyOnly(ApprovalRequest request) {
        return null;
    }

    /**
     * 不吃 allow 规则地判一次：deny 规则照样拦，其余一律往下问。沙箱外重跑用——用户存
     * {@code git push origin main} 时批的是「推送」，不是「出沙箱」（照 Claude Code：沙箱外重跑单独确认）。
     * 只有规则层（{@link PolicyGate}）知道规则，别的实现就是 {@link #decide}。
     */
    default ApprovalVerdict decideIgnoringAllowRules(ApprovalRequest request) {
        return decide(request);
    }

    /** 撞上了 ask 规则：这一下一定要问人（照 Claude Code）。只有规则层知道。 */
    default boolean requiresHuman(ApprovalRequest request) {
        return false;
    }

    /** 非交互环境的安全默认值：没人能确认，那就都不批。 */
    static ApprovalPolicy denyAll() {
        return request -> ApprovalVerdict.no("当前没有可以确认的人，危险动作一律不执行。");
    }

    /** 仅用于测试。<b>没有「用户说别再问我了」这条路了</b>——那条路现在叫规则，见 {@link ApprovalRules}。 */
    static ApprovalPolicy approveAll() {
        return request -> ApprovalVerdict.yes();
    }
}

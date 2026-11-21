package dev.wiles.myagent.tool.safety;

/**
 * 替人审一个要授权的动作（auto 模式）。见 {@link AutoClassifier}。
 */
@FunctionalInterface
public interface AutoReviewer {

    /** @return 放行或拒绝；返回 null 表示「交还给人」——审不下去了（比如连拒太多次） */
    ApprovalVerdict review(ApprovalRequest request);

    /**
     * 派出子 agent 之前查一次（照 Claude Code：派出是 agent 自己的选择，这时候还认得出来）。可以拒。
     *
     * @return 放行或拒绝；null 表示交还给人
     */
    default ApprovalVerdict reviewDelegation(String agentType, String task) {
        return ApprovalVerdict.yes();
    }

    /**
     * 子 agent 做完、报告交回主 agent 之前查一次它做过的事和报告（照 Claude Code）。只警告，不丢结果。
     *
     * @return 给主 agent 的警告；没问题返回 null
     */
    default String reviewHandoff(String report) {
        return null;
    }

    /**
     * 注入探针（照 Claude Code 的输入侧防线）：工具输出进上下文之前扫一遍，像是冲着 agent 来的指令就给一句警告。
     *
     * @return 加在输出前面的警告；没事返回 null
     */
    default String probeToolOutput(String tool, String output) {
        return null;
    }
}

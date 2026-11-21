package dev.wiles.myagent.tool.safety;

/**
 * 替人审一个要授权的动作（auto 模式）。见 {@link AutoClassifier}。
 */
@FunctionalInterface
public interface AutoReviewer {

    /** @return 放行或拒绝；返回 null 表示「交还给人」——审不下去了（比如连拒太多次） */
    ApprovalVerdict review(ApprovalRequest request);
}

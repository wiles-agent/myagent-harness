package dev.wiles.myagent.tool.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 包在「问人」那一层外面：要问人的命令如果<b>只是</b>在用解释器跑代码，先交给 {@link CodeTriage} 读代码判风险——
 * 低风险直接放行，高风险照常问人，问的时候把审查的理由接在原因后面。
 *
 * <pre>
 *   PolicyGate → 留底 → AutoReviewGate（auto / dontAsk）→ PermissionRequest hook → CodeTriageGate → 问人
 * </pre>
 *
 * 不归它管、照常问人的：不是 bash、命令里夹着别的要审批的段、撞上了 ask 规则（那是用户说了「这个一定问我」）。
 */
public final class CodeTriageGate implements ApprovalPolicy {

    private static final Logger log = LoggerFactory.getLogger(CodeTriageGate.class);

    private final ApprovalPolicy human;
    private final CodeTriage triage;

    public CodeTriageGate(ApprovalPolicy human, CodeTriage triage) {
        this.human = human;
        this.triage = triage;
    }

    @Override
    public ApprovalVerdict decide(ApprovalRequest request) {
        if (request.kind() != ApprovalKind.BASH || ApprovalScope.humanRequired()) {
            return human.decide(request);
        }
        List<CommandGuard.CodeRun> runs = CommandGuard.codeRuns(request.subject());
        if (runs == null) {
            return human.decide(request);
        }
        CodeTriage.Result result = triage.judge(request.subject(), runs);
        if (result.low()) {
            log.info("代码风险审查判低风险，不问人: {}（{}）", request.subject(), result.reason());
            return ApprovalVerdict.yes();
        }
        return human.decide(new ApprovalRequest(request.kind(), request.action(), request.subject(),
                request.reason() + "；代码风险审查：" + result.reason(), request.preview(), request.origin()));
    }
}

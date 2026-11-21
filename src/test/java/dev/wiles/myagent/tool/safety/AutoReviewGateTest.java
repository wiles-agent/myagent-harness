package dev.wiles.myagent.tool.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoReviewGateTest {

    private final List<String> asked = new ArrayList<>();

    /** 记账的「人」：被问一次记一笔，一律同意。 */
    private final ApprovalPolicy human = request -> {
        asked.add(request.subject());
        return ApprovalVerdict.yes();
    };

    private static final ApprovalRequest RM = ApprovalRequest.bash("rm -rf build", "rm");

    @AfterEach
    void clear() {
        ApprovalScope.clear();
    }

    @Test
    @DisplayName("没在 auto 下：照常交给下一层（人）")
    void passesThroughWithoutReviewer() {
        assertTrue(new AutoReviewGate(human, true).decide(RM).approved());
        assertEquals(List.of("rm -rf build"), asked);
    }

    @Test
    @DisplayName("auto 下：审查说了算，不问人")
    void reviewerDecides() {
        ApprovalScope.set(null, false, request -> ApprovalVerdict.no("越界"));
        ApprovalVerdict v = new AutoReviewGate(human, true).decide(RM);
        assertFalse(v.approved());
        assertTrue(asked.isEmpty(), "auto 下不该问人: " + asked);

        ApprovalScope.set(null, false, request -> ApprovalVerdict.yes());
        assertTrue(new AutoReviewGate(human, true).decide(RM).approved());
        assertTrue(asked.isEmpty());
    }

    @Test
    @DisplayName("审查交还给人（null）：有人可问就问人；没人可问（无头）就拒——无头的下一层是「全都批」，交给它等于放行")
    void escalation() {
        ApprovalScope.set(null, false, request -> null);
        assertTrue(new AutoReviewGate(human, true).decide(RM).approved());
        assertEquals(List.of("rm -rf build"), asked);

        asked.clear();
        ApprovalVerdict headless = new AutoReviewGate(human, false).decide(RM);
        assertFalse(headless.approved());
        assertTrue(asked.isEmpty());
    }

    @Test
    @DisplayName("dontAsk 也在这一层拒：无头运行的下一层是「全都批」，以前 dontAsk 在无头下根本不生效")
    void dontAskIsEnforcedHere() {
        ApprovalScope.set(null, true);
        assertFalse(new AutoReviewGate(human, false).decide(RM).approved());
        assertTrue(asked.isEmpty());
    }
}

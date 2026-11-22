package dev.wiles.myagent.tool.sandbox;

import dev.wiles.myagent.tool.safety.ApprovalKind;
import dev.wiles.myagent.tool.safety.ApprovalRule;
import dev.wiles.myagent.tool.safety.ApprovalRules;
import dev.wiles.myagent.tool.safety.ApprovalVerdict;
import dev.wiles.myagent.tool.safety.PolicyGate;
import dev.wiles.myagent.tool.safety.PolicyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 代理问「这个主机能不能连」时，NETWORK 的 deny / ask 规则对名单上的主机也生效。 */
class SandboxRunnerTest {

    @TempDir
    Path tmp;

    private final List<String> asked = new ArrayList<>();

    private NetworkProxy.HostDecider decider(ApprovalRules rules, boolean human) {
        PolicyStore store = new PolicyStore(tmp.resolve("policy.json"));
        store.save(rules);
        PolicyGate gate = new PolicyGate(request -> {
            asked.add(request.subject());
            return human ? ApprovalVerdict.yes() : ApprovalVerdict.no();
        }, store);
        return SandboxRunner.hostDecider(gate);
    }

    @Test
    @DisplayName("deny 规则：判死，不问人")
    void denyRule() {
        var d = decider(ApprovalRules.of(List.of(),
                List.of(ApprovalRule.of(ApprovalKind.NETWORK, "*.evil.example", "d"))), true);
        assertEquals(Boolean.FALSE, d.ruled("x.evil.example"));
        assertTrue(asked.isEmpty());
    }

    @Test
    @DisplayName("ask 规则：一定问人，名单上有也问")
    void askRule() {
        var d = decider(ApprovalRules.of(List.of(), List.of(),
                List.of(ApprovalRule.of(ApprovalKind.NETWORK, "pypi.org", "d"))), false);
        assertEquals(Boolean.FALSE, d.ruled("pypi.org"));
        assertEquals(List.of("pypi.org"), asked);
    }

    @Test
    @DisplayName("没撞规则：交给名单（null）")
    void noRule() {
        var d = decider(ApprovalRules.of(List.of(),
                List.of(ApprovalRule.of(ApprovalKind.NETWORK, "*.evil.example", "d"))), true);
        assertNull(d.ruled("github.com"));
        assertTrue(asked.isEmpty());
    }
}

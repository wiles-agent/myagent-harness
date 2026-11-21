package dev.wiles.myagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.wiles.myagent.context.TokenLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** usage 里的缓存命中：DeepSeek 和 OpenAI 兼容两种写法，流式和非流式两条路，最后记进账本。 */
class UsageTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    @DisplayName("DeepSeek 的 prompt_cache_hit_tokens、OpenAI 的 prompt_tokens_details.cached_tokens 都认；都没有是 0")
    void readsBothCacheFields() throws Exception {
        String deepseek = """
                {"prompt_tokens":1000,"completion_tokens":50,"total_tokens":1050,
                 "prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":200}""";
        String openai = """
                {"prompt_tokens":1000,"completion_tokens":50,"total_tokens":1050,
                 "prompt_tokens_details":{"cached_tokens":640}}""";
        String plain = "{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}";

        assertEquals(800, M.readValue(deepseek, ChatResponse.Usage.class).cachedPromptTokens());
        assertEquals(640, M.readValue(openai, ChatResponse.Usage.class).cachedPromptTokens());
        ChatResponse.Usage p = M.readValue(plain, ChatResponse.Usage.class);
        assertEquals(0, p.cachedPromptTokens());
        assertEquals(10, p.promptTokens());
        assertEquals(2, p.completionTokens());
        assertEquals(800, ChatResponse.Usage.of(M.readTree(deepseek)).cachedPromptTokens());

        ChatResponse response = M.readValue("{\"id\":\"x\",\"choices\":[],\"usage\":" + deepseek + "}", ChatResponse.class);
        TokenLedger ledger = TokenLedger.EMPTY.plus(response).plus(response);
        assertEquals(1600, ledger.cachedPromptTokens());
        assertEquals(2000, ledger.promptTokens());
    }
}

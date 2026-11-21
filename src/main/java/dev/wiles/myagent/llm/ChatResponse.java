package dev.wiles.myagent.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * /chat/completions 的响应体。
 *
 * finishReason 是 agent 循环的关键分支信号：
 *   "stop"       -> 模型说完了，本轮结束
 *   "tool_calls" -> 模型要调工具，执行完把结果回传再来一轮
 *   "length"     -> 撞到 max_tokens 被截断
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(
        String id,
        String model,
        List<Choice> choices,
        Usage usage
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            ChatMessage message,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    /**
     * @param cachedPromptTokens 提示词里命中服务端前缀缓存的部分。DeepSeek 叫 {@code prompt_cache_hit_tokens}，
     *                           OpenAI 兼容的服务放在 {@code prompt_tokens_details.cached_tokens}；都没有就是 0
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens,
            @JsonProperty("prompt_cache_hit_tokens") int cachedPromptTokens
    ) {
        public Usage(int promptTokens, int completionTokens, int totalTokens) {
            this(promptTokens, completionTokens, totalTokens, 0);
        }

        /** 从原始的 usage 节点读，两种写法都认。流式那条路用（非流式走上面的注解）。 */
        public static Usage of(com.fasterxml.jackson.databind.JsonNode u) {
            int cached = u.hasNonNull("prompt_cache_hit_tokens") ? u.get("prompt_cache_hit_tokens").asInt()
                    : u.path("prompt_tokens_details").path("cached_tokens").asInt();
            return new Usage(u.path("prompt_tokens").asInt(), u.path("completion_tokens").asInt(),
                    u.path("total_tokens").asInt(), cached);
        }

        /** 非流式响应：DeepSeek 的字段由注解接住，OpenAI 那种嵌套写法在这里补。 */
        @com.fasterxml.jackson.annotation.JsonCreator
        static Usage fromJson(com.fasterxml.jackson.databind.JsonNode u) {
            return of(u);
        }
    }

    /** 我们不开 n>1，永远只取第一个 choice。 */
    @JsonIgnore
    public ChatMessage message() {
        if (choices == null || choices.isEmpty()) {
            throw new LlmException("模型响应里没有 choices: " + id);
        }
        return choices.get(0).message();
    }

    @JsonIgnore
    public String finishReason() {
        return choices == null || choices.isEmpty() ? null : choices.get(0).finishReason();
    }

    @JsonIgnore
    public boolean wantsToolCall() {
        return message().hasToolCalls();
    }

    /** 纯文本回复；模型只发工具调用时 content 为 null，这里统一成空串。 */
    @JsonIgnore
    public String text() {
        String c = message().content();
        return c == null ? "" : c;
    }
}

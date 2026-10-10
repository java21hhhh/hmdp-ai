package com.hxr.hmdpai.usage;

import org.springframework.ai.chat.metadata.Usage;

/**
 * 一次请求的<b>总账</b>：整个 Agent 流程（含所有工具调用轮次）一共烧了多少 token。
 *
 * <h1>它和 {@link RoundUsageRecorder.Round} 的区别</h1>
 * <ul>
 *   <li>{@code TokenUsage}（本类）= <b>总账</b>。一次请求一行，回答"这次花了多少"</li>
 *   <li>{@code Round} = <b>明细</b>。一次请求 N 行，回答"钱花在哪一轮"</li>
 * </ul>
 *
 * <h1>这个总账是谁算的</h1>
 *
 * <p><b>是 Spring AI 算的，不是我们算的。</b>
 * 别自己把 {@code Round} 加起来 —— 框架内部的 {@code UsageAccumulator}
 * 已经在工具调用循环里逐轮累加了，最后写回最终响应。
 * 我们要做的只是"别把它扔了"（以前写的 {@code .content()} 就是把它扔了）。
 *
 * <p>这是个值得记住的习惯：<b>动手自己算之前，先确认框架是不是已经算好了。</b>
 *
 * @param promptTokens     整个请求发出去的 token 总量（所有轮次相加）
 * @param completionTokens 整个请求收回来的 token 总量
 * @param totalTokens      两者之和
 * @param estimatedCostCny 估算费用（元）。★ 这是估算，不是账单 ★
 */
public record TokenUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens,
        double estimatedCostCny) {

    /**
     * DeepSeek 的价目表：<b>元 / 百万 token</b>。
     *
     * <p>⚠️ <b>价格是会变的</b>，而且分"缓存命中 / 未命中"两档（这里按未命中的常规价估）。
     * 所以这两个数只是<b>量级参考</b>，不是账单 —— 真要看账，去 DeepSeek 控制台。
     * 想自己核对，改这两个常量即可，别处不用动。
     *
     * <p>放在类的开头、命名成常量，是给未来的你留的记号：
     * <b>凡是"会变的外部事实"，都不要散落在代码中间，要集中在一个地方。</b>
     */
    private static final double PRICE_PER_MILLION_INPUT = 2.0;
    private static final double PRICE_PER_MILLION_OUTPUT = 8.0;

    /** 从框架给的 Usage 转换过来。调用失败时 Usage 可能是空的，所以处处判 null。 */
    public static TokenUsage of(Usage usage) {
        if (usage == null) {
            return empty();
        }
        int prompt = nz(usage.getPromptTokens());
        int completion = nz(usage.getCompletionTokens());
        return new TokenUsage(prompt, completion, nz(usage.getTotalTokens()), estimateCost(prompt, completion));
    }

    public static TokenUsage empty() {
        return new TokenUsage(0, 0, 0, 0.0);
    }

    private static double estimateCost(int promptTokens, int completionTokens) {
        return promptTokens / 1_000_000.0 * PRICE_PER_MILLION_INPUT
                + completionTokens / 1_000_000.0 * PRICE_PER_MILLION_OUTPUT;
    }

    /** 日志里一行能看懂。费用保留 4 位小数 —— 单次请求的量级就是几分之一厘。 */
    public String describe() {
        return "prompt=%d  completion=%d  合计=%d tokens，约 ¥%.4f"
                .formatted(promptTokens, completionTokens, totalTokens, estimatedCostCny);
    }

    private static int nz(Integer value) {
        return (value == null) ? 0 : value;
    }
}

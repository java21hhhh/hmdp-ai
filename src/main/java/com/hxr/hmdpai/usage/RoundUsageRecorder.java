package com.hxr.hmdpai.usage;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ 逐轮记账器 ★ —— 记录 Agent 每一次请求里，模型被调了几轮、每轮烧了多少 token。
 *
 * <h1>为什么需要它</h1>
 *
 * <p>一个 {@code /ai/agent} 请求，模型往往要被调用好几次：
 * <pre>
 *   第 1 轮  我们 → 模型：用户问这个，你有这些工具，怎么办？
 *   第 2 轮  模型 → 我们：我要调 searchShopsByName("火锅")
 *   第 3 轮  我们 → 模型：给你结果（把工具返回值塞进去）
 *   ...      一直循环到模型不再要求调工具
 * </pre>
 *
 * <p><b>每一轮都是一次完整的 HTTP 请求，都要花钱。</b>
 * 而且下一轮要把<b>整个上下文重发一遍</b>（system prompt + 记忆 + 之前所有轮次），
 * 所以 <b>prompt tokens 是一轮比一轮大的</b>。
 *
 * <p>只会看总数的话，你只知道自己花了 3500 个 token，
 * 但不知道这 3500 是怎么涨上来的 —— 也就无从下手去优化。
 *
 * <h1>为什么能用 observation 拿到这些数据</h1>
 *
 * <p>Spring AI 每调一次模型，都会发一个"观测事件"（observation）。
 * 这件事是给监控系统用的（micrometer），但它的载荷里带着我们要的 usage。
 * 我们做的事就是<b>在旁边挂一个监听器，把每一条记下来</b>。
 *
 * <p>这跟 Servlet 的 Filter / Listener 是同一个思路：
 * <b>框架在关键位置埋了钩子，你不改它的代码，也能在钩子上挂自己的逻辑。</b>
 *
 * <h1>那个 ThreadLocal 是干什么的</h1>
 *
 * <p>{@code onStop()} 是框架回调我们的，它<b>不知道当前是哪个请求在跑</b>。
 * 但模型调用发生在我们处理请求的那条线程上，所以可以借 ThreadLocal
 * 把"这次请求的账本"挂在当前线程上：
 * <ul>
 *   <li>请求开始时 {@link #begin()} —— 挂一个空账本上去</li>
 *   <li>框架每轮结束回调 {@code onStop} —— 往当前线程的账本里记一笔</li>
 *   <li>请求结束时 {@link #end()} —— 把账本摘下来，顺便 {@code remove()} 掉</li>
 * </ul>
 *
 * <p>⚠️ 这条路的成立前提是"整个工具调用循环跑在同一条线程上"。
 * 我们用的是阻塞式的 {@code .call()}，成立。如果哪天改成流式的 {@code .stream()}，
 * 就要重新想 —— <b>这是 ThreadLocal 这类方案共同的软肋</b>。
 */
@Component
public class RoundUsageRecorder implements ObservationHandler<ChatModelObservationContext> {

    /**
     * 一次模型调用的用量。
     *
     * @param index            第几轮（从 1 开始，方便你对着日志数）
     * @param promptTokens     这一轮【发出去】的 token —— 就是那个会涨的数
     * @param completionTokens 这一轮【收回来】的 token
     * @param totalTokens      两者之和
     */
    public record Round(int index, int promptTokens, int completionTokens, int totalTokens) {

        /**
         * 让日志里直接打这一个对象就能看懂，不用写一堆占位符。
         * 这就是我们前面给 {@code AgentResult} 写注释时说的"给人看的东西"。
         */
        @Override
        public String toString() {
            return "第%d轮  prompt=%-5d completion=%-5d 合计=%d".formatted(
                    index, promptTokens, completionTokens, totalTokens);
        }
    }

    /**
     * 当前请求的账本。
     *
     * <p>{@code ThreadLocal} 的意思是"这个变量每条线程各有一份" ——
     * 三个用户同时请求（还记得下午日志里那三个 {@code nio-8082-exec-1/2/3} 吗），
     * 各记各的账，不会串。
     */
    private static final ThreadLocal<List<Round>> CURRENT = new ThreadLocal<>();

    /** 请求开始：挂一个空账本。 */
    public static void begin() {
        CURRENT.set(new ArrayList<>());
    }

    /**
     * 请求结束：取下账本。
     *
     * <p>★ 一定要 {@code remove()}：Tomcat 的线程是<b>复用</b>的，
     * 不清理的话这个线程下次接到别人的请求时，账本还是旧的。
     * 这跟"用完 ThreadLocal 要 remove"是同一个铁律，忘了就是内存泄漏 + 数据串号。
     */
    public static List<Round> end() {
        List<Round> rounds = CURRENT.get();
        CURRENT.remove();
        return (rounds == null) ? List.of() : List.copyOf(rounds);
    }

    /**
     * 告诉框架：我只关心"模型调用"这一类事件，别的事件（HTTP 请求之类的）不要叫我。
     *
     * <p>不加这个判断的话，你项目里每一类 observation 都会回调到这里。
     */
    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    /**
     * 一轮模型调用结束时，框架回调这里。
     *
     * <p>{@code context.getResponse()} 就是这一轮的返回，里面带着 usage。
     * 注意这里是<b>单轮</b>的用量 —— 正是我们要的细粒度账。
     * （总账是框架的另一套机制在累加，见 {@code AiAgentService} 里的说明。）
     */
    @Override
    public void onStop(ChatModelObservationContext context) {
        List<Round> sink = CURRENT.get();

        // 没有账本 = 这次模型调用不是我们发的请求触发的。
        // 比如 Spring Boot 启动时的健康探活、或者以后接的别的定时任务。
        // 不管它们，不然会污染请求的账。
        if (sink == null) {
            return;
        }

        if (context.getResponse() == null
                || context.getResponse().getMetadata() == null) {
            return;
        }

        Usage usage = context.getResponse().getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        // token 数有可能是 null（比如调用失败时用的是 EmptyUsage），统一当 0 处理，
        // 免得后面做加法时抛 NullPointerException。
        sink.add(new Round(
                sink.size() + 1,
                nz(usage.getPromptTokens()),
                nz(usage.getCompletionTokens()),
                nz(usage.getTotalTokens())));
    }

    private static int nz(Integer value) {
        return (value == null) ? 0 : value;
    }
}

package com.hxr.hmdpai.service;

import com.hxr.hmdpai.client.HmdpClient;
import com.hxr.hmdpai.tool.HmdpTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ★★★ Agent 版的核心。★★★
 *
 * <h1>先看一个数字对比</h1>
 * <pre>
 *   AiRecommendService (Workflow 版)   262 行，五步流程写死
 *   AiAgentService     (Agent   版)     约 70 行，其中一半还是注释
 * </pre>
 *
 * <p>少了什么？少了这五步里除了最后一步之外的全部：
 * <b>搜店没了、取前 N 家没了、挨个查券没了、拼上下文没了。</b>
 *
 * <p>它们跑去哪儿了？<b>没有了就是没有了。</b>
 * 模型现在自己决定搜什么、查哪几家、查几次。
 * 我们只给它两把工具（{@link HmdpTools}）和一段说明书（下面的 system prompt）。
 *
 * <h1>那代码都省了，是不是活儿也变少了？</h1>
 *
 * <p><b>不是，活儿换地方了。</b>原来你写的是"流程"，现在你写的是两样东西：
 * <ol>
 *   <li><b>工具集</b> —— 模型能力的<b>天花板</b>。你给两把工具，它就只有这两条路</li>
 *   <li><b>System prompt</b> —— 模型行为的<b>地板</b>。决定它守不守规矩、会不会轻易放弃</li>
 * </ol>
 * 一句要记住的话：<b>Agent 开发不是"少写代码"，是"从写流程改成写约束"。</b>
 * 约束写不好，你得到的不是"更聪明的助手"，而是"一只会乱翻冰箱的猫"。
 *
 * <h1>和 Workflow 版的三个可观测差别</h1>
 * <ol>
 *   <li><b>更慢、更贵</b>。Workflow 版固定调 1 次模型；
 *       Agent 版每调一次工具就要多问一次模型，共 N+1 次。返回里带了耗时，你自己看</li>
 *   <li><b>每次结果不一样</b>。同样的输入，模型这次查 3 次、下次可能查 2 次。
 *       这不是 bug，是 Agent 的本质特征，也是它最难上生产的原因</li>
 *   <li><b>能"想办法"</b>。Workflow 版搜不到就结束；Agent 版会自己换个词再试</li>
 * </ol>
 */
@Service
public class AiAgentService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentService.class);

    /**
     * System prompt —— Agent 版里，这段文字的分量比 Workflow 版重得多。
     *
     * <p>Workflow 版里，流程是我用 Java 保证的，prompt 只管"别说瞎话"。
     * Agent 版里，流程交给了模型，<b>prompt 就成了唯一的行为约束</b>。
     *
     * <p>逐条解释一下为什么这么写：
     *
     * <ol>
     *   <li><b>"所有信息都必须来自工具返回"</b> —— 防幻觉。<br>
     *       这和 Workflow 版是同一条，但重要程度完全不一样。
     *       Workflow 版里，模型手上只有我喂的数据，想编也没材料；
     *       Agent 版里，模型知道有"店铺""优惠券"这些概念，
     *       你不拦着，它真能编出一家不存在的火锅店，还说得有鼻子有眼</li>
     *
     *   <li><b>"先想清楚还缺什么，不要只调一次就下结论"</b> —— 逼它多步推理。<br>
     *       不写这条，模型经常搜一次就急着回答。这条是把"能查"变成"会查"</li>
     *
     *   <li><b>★"结果不理想就换个思路再试" —— 这条是 Workflow 和 Agent 的分水岭 ★</b><br>
     *       昨天「火锅」那个用例就卡在这儿：搜到 2 家、都没券，Workflow 版
     *       老老实实报告"这两家没券"。这句话就是我明确告诉模型：
     *       <b>别停，想办法。</b>
     *       不过等下你会看到——它"想办法"的方式有多笨，取决于你给了它什么工具</li>
     *
     *   <li><b>字数限制</b> —— 控制成本和可读性。不加限制，模型能给你写篇小作文</li>
     * </ol>
     */
    private static final String SYSTEM_PROMPT = """
            你是一个本地生活推荐助手，可以调用工具查询真实的店铺和优惠券数据。

            工作方式：
            1.你说的所有信息都必须来自工具返回的结果，或者来自上面这段对话里
            已经查证过的内容。除此之外一律回答"我查不到"，
            绝对不要编造店名、地址、人均价格、评分或优惠券
            2. 用户的问题往往需要查好几次。先想清楚还缺什么信息，再决定调哪个工具，
               不要只调一次就急着下结论。
            3. 如果一次查询的结果不理想（比如搜到的店铺都不满意，或者没有优惠券），
               换个思路再试试 —— 换个关键词，或者从已有结果里找别的线索接着查。不要直接放弃。
            4. 用自然、口语化的中文回答，200 字以内，不要说套话。
            """;

    private final HmdpClient hmdpClient;
    private final ChatClient chatClient;

    /**
     * ★ 会话记忆 ★ —— 换掉了下午手写的那个 {@code Map<String, List<Message>>}。
     *
     * <p>手写版要自己管的 4 件事，现在分别归谁管：
     * <ul>
     *   <li>存在哪     → {@link ChatMemoryRepository}（现在是 {@link InMemoryChatMemoryRepository}，
     *                   所以重启就没了；以后想活过重启，换这一行的实现类即可）</li>
     *   <li>留最近几条 → {@link MessageWindowChatMemory} 的 {@code maxMessages(N)}</li>
     *   <li>这是谁的   → {@link ChatMemory#CONVERSATION_ID}</li>
     *   <li>存哪些消息 → {@link MessageChatMemoryAdvisor}（一问一答自动存，连工具调用都成对存）</li>
     * </ul>
     *
     * <p><b>手写一遍的价值在这儿</b>：不写过，你不知道上面这 4 行分别在管什么。
     */
    private final ChatMemory chatMemory;
    /**
     * 注意这里的注入和 Workflow 版一模一样：{@code ChatClient.Builder} 是 Spring AI
     * 自动装配好的，我们自己 {@code build()}。
     *
     * <p><b>两个版本用的是同一个 ChatClient，一行配置都没改。</b>
     * 变的只有调用方式 —— 那边是 {@code .user(facts)}（给数据），
     * 这边是 {@code .tools(tools)}（给能力）。这是今天最值得记住的一句话。
     */
    public AiAgentService(HmdpClient hmdpClient, ChatClient.Builder chatClientBuilder) {
        this.hmdpClient = hmdpClient;

        // 只留最近 20 条。把这个数改成 2，就是下午那个"第 4 轮忘了名字"的实验。
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();

        // 记忆是和工具并列的一条"能力"，挂在 ChatClient 上
        this.chatClient = chatClientBuilder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 把问题交给模型，让它用工具自己去查。
     *
     * <p>整个方法就一个要点：{@code .tools(tools)} 这一行。
     * 加不加它，就是 Workflow 和 Agent 的全部区别。
     *
     * <p>执行过程（你看不代码，因为它发生在 Spring AI 内部）：
     * <pre>
     *   第 1 轮  我们 → 模型：用户问这个，你有这两把工具，怎么办？
     *   第 2 轮  模型 → 我们：我要调 searchShopsByName("火锅")
     *   第 3 轮  我们 → 模型：给你结果，2 家店，id 是 5 和 7
     *   第 4 轮  模型 → 我们：那我再调 listVouchers(5)
     *   ...      （一直循环到模型不再要求调工具为止）
     *   最后一轮 模型 → 我们：这是最终答案（纯文字）
     * </pre>
     * 每一轮都是一次真实的 HTTP 请求，都要花钱、花时间。
     * 返回里的 {@code elapsedMs} 和 {@code toolCalls.size()} 就是这笔账。
     */
    public AgentResult chat(String question, String token, Long confirmVoucherId) {

        // 现在只要这一个 key 了 —— 取历史、存历史、裁剪窗口，全归 MessageChatMemoryAdvisor 管。
        String key = (token != null) ? token : "anonymous";

        // 每个请求 new 一个工具对象，这样 trace 里记录的就只会是这一次的调用
        HmdpTools tools = new HmdpTools(hmdpClient,token,confirmVoucherId);

        // 先把问题打出来。这样控制台里一次请求的记录是"完整的、按时间排的"：
        //   问题 → 工具调用 → 模型回答
        // 忘了这行的话，你得翻到最底下才知道上面那堆日志是回答哪个问题的。
        log.info("[Agent] ========== 收到问题：{}", question);

        // ★★★ 把模型拿不到的数据喂给它 ★★★
        //
        // 为什么需要这个？
        //   工具返回的内容【不进记忆】（记忆里只有"用户说的话"和"模型说的话"）。
        //   所以到第 2 轮，模型手上根本没有券的 id —— 它只能猜。
        //   实测它猜过 0、-1、2，三次三个数，全都是编的。
        //
        // 为什么我们能直接告诉它？
        //   因为这个 id 就在 URL 的 confirm 上，我们一直看得见。
        //   ⚠️ 注意：告诉它 id ≠ 给它授权。真正的授权仍然是
        //   HmdpTools 里那道门（confirmVoucherId.equals(voucherId)），
        //   模型知道了 id 也只能"照对"，伪造不了。
        //
        // 三次改文字（工具描述、system prompt）都没能让它不猜 —— 这次不改文字，改数据。
        String systemPrompt = SYSTEM_PROMPT;
        if (confirmVoucherId != null) {
            systemPrompt = SYSTEM_PROMPT
                    + "\n\n【本次请求的附加信息】用户在页面上已经确认，要抢的是 voucherId="
                    + confirmVoucherId + " 这张券。直接用它调用 seckillVoucher，不要自己另找 id。";
        }

        long startedAt = System.currentTimeMillis();
        String answer;
        try {
            answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(question)
                    // 告诉 advisor：这次是【谁】的对话。
                    // 不写这行，所有人共用一份记忆 —— 比没有记忆还可怕。
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, key))
                    // ★★★ 就是这一行。★
                    // 加了它 = Agent：模型可以自己决定去调哪些方法
                    // 去掉它 = 一个普通的问答机器人：模型只能凭你给的字面信息回答
                    .tools(tools)
                    .call()
                    .content();
        } catch (Exception e) {
            // 调模型失败的原因很多（key 没配、欠费、网络、限流），
            // 堆栈对人不友好，但一定要打全，不然没法排查。
            log.error("调用模型失败", e);
            return new AgentResult(question,
                    "调用模型失败了。最常见的原因是环境变量 DEEPSEEK_API_KEY 没设上 —— "
                            + "去启动窗口看看堆栈里是不是有 'Your api key: ****KEY} is invalid'。"
                            + "原始错误:" + e.getMessage(),
                    tools.trace(),
                    System.currentTimeMillis() - startedAt);
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        List<String> calls = tools.trace();

        log.info("[Agent] 模型共调用 {} 次工具，耗时 {} ms", calls.size(), elapsed);
        log.info("[Agent] 调用路径：{}", calls);

        // ★ 把模型的最终回答也打出来。
        // 不打这行的话，你只有浏览器里能看到回答 ——
        // 而排查问题的时候，你要的恰恰是"问题、工具调用、回答"三样摆在一起看。
        // 换行用 \n：回答是多行的，挤成一行没法读。
        log.info("[Agent] 模型回答：\n{}", answer);

        return new AgentResult(question, answer, calls, elapsed);
    }

    /**
     * 返回给调用方的结果。
     *
     * <p>{@code toolCalls} 和 {@code elapsedMs} 这两个字段是<b>给你看的，不是给用户看的</b>。
     * 它们回答两个问题：
     * <ul>
     *   <li>"模型到底想了什么？" —— 看 {@code toolCalls}，这是 Agent 的"脑回路"</li>
     *   <li>"Agent 比 Workflow 贵多少？" —— 看 {@code elapsedMs}，
     *       每多一次工具调用 = 多一轮模型请求</li>
     * </ul>
     * 在真实项目里，这两个数字是要进监控面板的：调用次数异常上涨 = 模型在死循环，
     * 耗时飙升 = 工具变慢了。**"能观测"是 Agent 能不能上生产的前提。**
     *
     * @param question   用户原问题
     * @param answer     模型最终的答复
     * @param toolCalls  模型这一轮依次调用了哪些工具（按顺序）
     * @param elapsedMs  从头到尾花了多少毫秒
     */
    public record AgentResult(
            String question,
            String answer,
            List<String> toolCalls,
            long elapsedMs
    ) {
    }
}

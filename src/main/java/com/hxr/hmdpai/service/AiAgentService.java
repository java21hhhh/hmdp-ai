package com.hxr.hmdpai.service;

import com.hxr.hmdpai.client.HmdpClient;
import com.hxr.hmdpai.tool.HmdpTools;
import com.hxr.hmdpai.usage.RoundUsageRecorder;
import com.hxr.hmdpai.usage.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
            5.决定调用工具时，直接调用，不要在调用前输出任何解说文字（比如"我先搜索一下…"、"让我查查…"）
            """;

    private final HmdpClient hmdpClient;
    private final ChatClient chatClient;

    /**
     * ★ 会话记忆 ★ —— 换掉了下午手写的那个 {@code Map<String, List<Message>>}。
     *
     * <p>手写版要自己管的 4 件事，现在分别归谁管：
     * <ul>
     *   <li>存在哪     → {@link ChatMemoryRepository}。★ 2026-10-09 起是<b>数据库版</b>
     *                   （H2 文件库，见 application.yml），<b>重启不再丢记忆</b>。
     *                   以前这里是 {@code new InMemoryChatMemoryRepository()}，
     *                   现在改成从构造器注入 ——
     *                   换存储方式只要换 pom 里的依赖 + 改配置，<b>这个类的代码一行没动</b>。
     *                   这就是"面向接口"最直观的一次实感</li>
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
    public AiAgentService(HmdpClient hmdpClient,
                          ChatClient.Builder chatClientBuilder,
                          ChatMemoryRepository chatMemoryRepository) {
        this.hmdpClient = hmdpClient;

        // 只留最近 20 条。把这个数改成 2，就是下午那个"第 4 轮忘了名字"的实验。
        // ★ chatMemoryRepository 是 Spring 注入进来的（现在是数据库版），
        //   我们只负责在外面包一层"只留 20 条"的窗口。
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
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
    public AgentResult chat(String question, String token, String conversationId, Long confirmVoucherId) {

        // 现在只要这一个 key 了 —— 取历史、存历史、裁剪窗口，全归 MessageChatMemoryAdvisor 管。
        // 三档优先级的来龙去脉见下面 resolveKey() 的注释。
        String key = resolveKey(token, conversationId);

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
        String systemPrompt = buildSystemPrompt(confirmVoucherId);

        // ★ 开始记账 ★ —— 必须在调模型【之前】挂上账本，
        // 这样框架在每一轮结束时回调 RoundUsageRecorder.onStop() 才有地方记。
        // 挂晚了，第 1 轮的账就丢了。
        RoundUsageRecorder.begin();

        long startedAt = System.currentTimeMillis();
        String answer = null;
        Usage usage = null;
        String failure = null;
        try {
            // ★★★ 注意这里从 .content() 换成了 .chatResponse() ★★★
            //
            // .content() 只把回答的文字掏给你，其余全扔了 —— 包括账本。
            // .chatResponse() 把整个响应对象给你，里面有：
            //   getResult().getOutput().getText()  → 回答文字（就是原来 .content() 给的那个）
            //   getMetadata().getUsage()           → 这次请求的 token 账（【已经包含所有轮次】）
            //
            // 一句话：.content() 是"只要答案"，.chatResponse() 是"答案 + 它是怎么来的"。
            ChatResponse response = chatClient.prompt()
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
                    .chatResponse();

            answer = response.getResult().getOutput().getText();
            usage = response.getMetadata().getUsage();
        } catch (Exception e) {
            // 调模型失败的原因很多（key 没配、欠费、网络、限流），
            // 堆栈对人不友好，但一定要打全，不然没法排查。
            log.error("调用模型失败", e);
            failure = "调用模型失败了。最常见的原因是环境变量 DEEPSEEK_API_KEY 没设上 —— "
                    + "去启动窗口看看堆栈里是不是有 'Your api key: ****KEY} is invalid'。"
                    + "原始错误:" + e.getMessage();
        }

        // ★ 收账 ★ —— 成功失败都要走到这里，把账本从当前线程上摘下来。
        List<RoundUsageRecorder.Round> rounds = RoundUsageRecorder.end();

        long elapsed = System.currentTimeMillis() - startedAt;
        List<String> calls = tools.trace();
        TokenUsage total = TokenUsage.of(usage);

        if (failure != null) {
            // 失败也要把账带上：失败常常比成功更烧钱（模型重试、超时重发），
            // 只记成功的账，这部分成本你就永远看不见。
            log.info("[账本] 请求失败，但已经烧掉 {} 轮模型调用，{}", rounds.size(), total.describe());
            return new AgentResult(question, failure, calls, elapsed, total, rounds);
        }

        // ── 这就是"仪表盘"在控制台里的样子 ──────────────────────────
        log.info("[Agent] 模型共调用 {} 次工具，{} 轮模型请求，耗时 {} ms",
                calls.size(), rounds.size(), elapsed);
        log.info("[Agent] 调用路径：{}", calls);
        log.info("[账本] 本次请求总账：{}", total.describe());

        // ★★ 下面这两行才是重点 ★★
        // 逐轮打出来，你会看到 prompt 一轮比一轮大 ——
        // 因为每一轮都要把【整个上下文重发一遍】。
        // 只看总账是感受不到这件事的。
        for (RoundUsageRecorder.Round round : rounds) {
            log.info("[账本]   {}", round);
        }

        // ★ 把模型的最终回答也打出来。
        // 不打这行的话，你只有浏览器里能看到回答 ——
        // 而排查问题的时候，你要的恰恰是"问题、工具调用、回答"三样摆在一起看。
        // 换行用 \n：回答是多行的，挤成一行没法读。
        log.info("[Agent] 模型回答：\n{}", answer);

        return new AgentResult(question, answer, calls, elapsed, total, rounds);
    }

    /**
     * ★ 流式版 ★ —— 和 {@link #chat} 做的是**同一件事**，只是把答案一格一格吐出去。
     *
     * <h1>和 chat() 的区别只有一行</h1>
     * <pre>
     *   chat()    .call()    .chatResponse()   → 一次给你整个 ChatResponse
     *   chatStream().stream() .content()       → 给你一个 Flux&lt;String&gt;，一个词一个词地冒
     * </pre>
     * <b>工具、护栏、记忆、system prompt —— 全都没变，一行没动。</b>
     * 变的只是"怎么把结果拿回来"。这一点值得记住：<b>流式是"取结果的方式"，不是另一种 Agent。</b>
     *
     * <h1>Flux 是什么</h1>
     * 你可以先把它理解成"一个会陆续到货的 List"。
     * {@code List} 是你拿到手的时候东西就齐了；{@code Flux} 是"先给你个凭据，东西随后一件件送来"。
     * 这是响应式编程（Reactive）的基本概念，Java 里由 Reactor 这个库提供。
     *
     * <h1>⚠️ 这个版本【没有】账本，原因值得一看</h1>
     * {@link RoundUsageRecorder} 是靠 {@code ThreadLocal} 记账的 —— 而它成立的前提是
     * "整个流程跑在同一条线程上"（阻塞式 {@code .call()} 满足这个前提）。
     * 流式走的是 Reactor 的线程调度，这个假设不一定还成立，所以这里**故意没有开账本** ——
     * 宁可没有数，也不要一个错的数。
     *
     * <p>（如果你去看 {@code RoundUsageRecorder} 的类注释，会看到我当时就写了这个隐患。
     * 现在它变成真的了 —— <b>写注释的时候多想一步，未来会省你一次排查</b>。）
     */
    public Flux<String> chatStream(String question, String token, String conversationId, Long confirmVoucherId) {

        String key = resolveKey(token, conversationId);
        HmdpTools tools = new HmdpTools(hmdpClient, token, confirmVoucherId);
        String systemPrompt = buildSystemPrompt(confirmVoucherId);

        log.info("[Agent/stream] ========== 收到问题（流式）：{}", question);

        long startedAt = System.currentTimeMillis();

        return chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, key))
                .tools(tools)
                // ★★★ 唯一的区别就在这两个方法名上 ★★★
                .stream()
                .content()
                // doFinally：流"结束"时（正常结束/出错/被取消）都会走这里。
                // 用它来打日志 —— 因为流是异步的，方法体早返回了，
                // 你不能像 chat() 那样在最后一行直接写 log。
                .doFinally(signal -> log.info(
                        "[Agent/stream] 流结束（{}），耗时 {} ms，工具调用：{}",
                        signal, System.currentTimeMillis() - startedAt, tools.trace()));
    }

    /**
     * 算出这次请求的记忆桶名（"这是哪段对话"）。
     *
     * <p>★ <b>桶名（这是哪段对话）和凭证（你是谁）是两件事</b>，以前被焊成了同一个（都是 token）。
     * 后果有两个：
     * <ul>
     *   <li><b>产品缺陷</b>：同一个用户只能有一份对话，换个话题也得接着上一段聊</li>
     *   <li><b>评测没法跑</b>：真 token 加上时间戳就 401，所以真 token 那条 case
     *       的记忆永远串在一起，第二次跑模型直接背上一次的答案（实测过）</li>
     * </ul>
     *
     * <p>三档，优先级从高到低：
     * <ol>
     *   <li>传了 {@code X-Conversation-Id} → 用它（评测脚本每次换个新桶）</li>
     *   <li>没传，但有 token → 退回老行为（浏览器 / 前端一个字不用改）</li>
     *   <li>都没有 → anonymous</li>
     * </ol>
     */
    private String resolveKey(String token, String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationId;
        }
        return (token != null) ? token : "anonymous";
    }

    /**
     * 拼 system prompt。
     *
     * <p>这里有一个来之不易的修复，见第 9 条笔记：
     * 模型在工具调用之间会**反复猜券号**（实测猜过 0 / -1 / 2），改了三遍文字都没用。
     * 真正的原因是<b>"工具返回的内容不进记忆"</b> —— 模型手上根本没有那个 id。
     * 所以最后靠"把数据喂进它的上下文"解决，也就是下面这几行。
     *
     * <p>⚠️ 告诉它 id ≠ 给它授权：真正的门仍然是
     * {@code HmdpTools} 里那句 {@code confirmVoucherId.equals(voucherId)}。
     */
    private String buildSystemPrompt(Long confirmVoucherId) {
        if (confirmVoucherId == null) {
            return SYSTEM_PROMPT;
        }
        return SYSTEM_PROMPT
                + "\n\n【本次请求的附加信息】用户在页面上已经确认，要抢的是 voucherId="
                + confirmVoucherId + " 这张券。直接用它调用 seckillVoucher，不要自己另找 id。";
    }

    /**
     * 返回给调用方的结果。
     *
     * <p>{@code toolCalls} / {@code elapsedMs} / {@code usage} / {@code rounds}
     * 这几个字段是<b>给你看的，不是给用户看的</b>。它们回答四个问题：
     * <ul>
     *   <li>"模型到底想了什么？" —— 看 {@code toolCalls}，这是 Agent 的"脑回路"</li>
     *   <li>"Agent 比 Workflow 贵多少？" —— 看 {@code elapsedMs}，
     *       每多一次工具调用 = 多一轮模型请求</li>
     *   <li>"这次烧了多少钱？" —— 看 {@code usage}（总账，框架累加好的）</li>
     *   <li>"钱花在哪一轮了？" —— 看 {@code rounds}（逐轮明细，我们自己记的）</li>
     * </ul>
     * 在真实项目里，这几个数字是要进监控面板的：调用次数异常上涨 = 模型在死循环，
     * 耗时飙升 = 工具变慢了，token 异常上涨 = 上下文被撑爆了。
     * <b>"能观测"是 Agent 能不能上生产的前提。</b>
     *
     * @param question  用户原问题
     * @param answer    模型最终的答复
     * @param toolCalls 模型这一轮依次调用了哪些工具（按顺序）
     * @param elapsedMs 从头到尾花了多少毫秒
     * @param usage     整个请求的 token 总账 + 估算费用
     * @param rounds    逐轮的 token 明细（第 1 轮、第 2 轮……）。
     *                  <b>这个列表的长度 = 模型被调了几次</b>，
     *                  正常情况应该等于 {@code toolCalls.size() + 1}
     *                  （每次工具调用后要再问一轮，最后还有一轮出答案）；
     *                  对不上就说明模型在一轮里并行了多个工具调用
     */
    public record AgentResult(
            String question,
            String answer,
            List<String> toolCalls,
            long elapsedMs,
            TokenUsage usage,
            List<RoundUsageRecorder.Round> rounds
    ) {
    }
}

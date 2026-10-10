package com.hxr.hmdpai.controller;

import com.hxr.hmdpai.service.AiAgentService;
import com.hxr.hmdpai.service.AiRecommendService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 对外接口。跟 hmdp 里的 Controller 写法完全一样 —— 这正说明
 * <b>AI 服务本身就是一个普通 Spring Boot 服务</b>,不是什么特殊物种。
 *
 * <p>试一下(hmdp 后端得先跑起来):
 * <pre>
 * http://localhost:8082/ai/recommend?keyword=火锅
 * http://localhost:8082/ai/recommend?keyword=103
 * </pre>
 *
 * <p>返回的 JSON 里除了 {@code answer}(AI 写的推荐语),
 * 还有 {@code factsSentToModel} —— 那是发给模型的原始数据。
 * <b>一定要点开看一眼</b>,这是理解 AI 应用最直接的方式:
 * 你会立刻明白"模型的输出质量取决于你喂进去的上下文"这句话是什么意思。
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiRecommendService aiRecommendService;
    private final AiAgentService aiAgentService;

    public AiController(AiRecommendService aiRecommendService, AiAgentService aiAgentService) {
        this.aiRecommendService = aiRecommendService;
        this.aiAgentService = aiAgentService;
    }

    /**
     * Workflow 版：我写死流程，模型只负责把数据写成一段人话。
     *
     * <p>参数是 {@code keyword}（关键词）—— 因为流程是我定的，
     * 我只需要知道"搜什么词"就够了。
     */
    @GetMapping("/recommend")
    public AiRecommendService.RecommendResult recommend(@RequestParam("keyword") String keyword) {
        return aiRecommendService.recommend(keyword);
    }

    /**
     * Agent 版：我把工具交给模型，流程由模型自己决定。
     *
     * <p>★ 注意参数名变了：{@code keyword} → {@code question}。
     * <b>这不是随手改的，这是整个思维转变的外在表现。</b>
     * <ul>
     *   <li>Workflow 版要的是"一个搜索关键词" —— 因为我只会用它做一件事（搜店）</li>
     *   <li>Agent 版要的是"一句话问题" —— 因为我不知道模型会怎么理解它、
     *       会去调哪几个工具、调几次</li>
     * </ul>
     * 接口的入参从"参数"变成"意图"，往往就是这里从传统开发滑向 AI 开发的信号。
     *
     * <p>试一下（两个版本对照着看，差别一目了然）：
     * <pre>
     *  Workflow: http://localhost:8082/ai/recommend?keyword=火锅
     *  Agent   : http://localhost:8082/ai/agent?question=推荐一家有优惠券的店吃饭
     * </pre>
     *
     * <p>返回的 JSON 里除了 {@code answer}，还有 {@code toolCalls} ——
     * 那是模型这一轮依次调了哪些工具。
     * <b>一定要点开看「火锅」那条</b>：你会看到模型搜完之后发现没券，
     * 然后自己换了个词接着搜。那个"接着搜"的动作，就是 Agent 和 Workflow 的分界线。
     */
    @GetMapping("/agent")
    public AiAgentService.AgentResult agent(@RequestParam("question") String question,
    @RequestHeader(value = "authorization", required = false) String token ,
    @RequestHeader(value = "X-Conversation-Id", required = false) String conversationId,
     @RequestParam(value = "confirm", required = false) Long confirmVoucherId) {
        return aiAgentService.chat(question,token,conversationId,confirmVoucherId);
    }

    /**
     * ★ 流式版 Agent ★ —— 给【人】看的那道门。
     *
     * <h1>为什么不改上面那个 /agent，而是新开一个</h1>
     * <ul>
     *   <li>{@code /agent} 返回<b>一个完整的 JSON</b>。评测脚本用
     *       {@code json.loads(读完整响应)} 解析它 —— 流式是一格一格吐的，
     *       {@code json.loads} 会当场炸</li>
     *   <li>流式的价值是<b>让人看着舒服</b>（字一个一个冒出来）；
     *       JSON 的价值是<b>让程序好解析</b>。这两个目标本来就不一样</li>
     * </ul>
     * <b>结论：给机器用的接口别为了让新功能好看就改掉 —— 已经有东西在消费它了。</b>
     * 真实的系统里，同一个能力对外提供两三种不同形态的接口非常常见。
     *
     * <h1>produces = text/event-stream 是什么意思</h1>
     * 这是 SSE（Server-Sent Events）的 MIME 类型，浏览器专门认这个格式。
     * 用 {@code EventSource} 一接就能拿到陆续送来的数据 ——
     * <b>是服务器主动推，不是客户端反复问</b>。
     * （对比你之前了解的轮询：轮询是"每分钟问一次有没有新消息"，
     *   SSE 是"有新消息我直接推给你"。）
     *
     * <h1>怎么试</h1>
     * <pre>
     *   python3.12 C:\Users\HONOR\hmdp-ai-scripts\stream_check.py 火锅
     * </pre>
     * 别用浏览器直接开 —— 自定义头 {@code X-Api-Key} 浏览器地址栏发不了。
     */
    @GetMapping(value = "/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> agentStream(@RequestParam("question") String question,
                                    @RequestHeader(value = "authorization", required = false) String token,
                                    @RequestHeader(value = "X-Conversation-Id", required = false) String conversationId,
                                    @RequestParam(value = "confirm", required = false) Long confirmVoucherId) {
        return aiAgentService.chatStream(question, token, conversationId, confirmVoucherId);
    }
}

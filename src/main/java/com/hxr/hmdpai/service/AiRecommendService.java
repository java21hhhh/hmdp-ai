package com.hxr.hmdpai.service;

import com.hxr.hmdpai.client.HmdpClient;
import com.hxr.hmdpai.dto.ShopView;
import com.hxr.hmdpai.dto.VoucherView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★★ 这个类就是"AI 应用"的核心,也是今天唯一要真正看懂的类。★★★
 *
 * <h1>它在干什么</h1>
 * 接收一个关键词,走一条<b>写死的五步流程</b>,最后返回一段 AI 写的推荐语。
 *
 * <pre>
 *   ① 调 hmdp 搜店铺      ← 普通代码
 *   ② 取前 N 家           ← 普通代码
 *   ③ 挨个查优惠券         ← 普通代码
 *   ④ 把数据拼成一段文本    ← 普通代码
 *   ⑤ 把文本发给 DeepSeek  ← ★ 只有这一步是"AI"
 * </pre>
 *
 * <h1>为什么说这是 Workflow 而不是 Agent</h1>
 * 上面那五步顺序是<b>我写死的</b>。不管用户搜什么,代码都严格走这条路。
 * 模型在第 ④ 步之前根本不知道自己要处理什么,它只是被动地"把给它的数据写成一段人话"。
 *
 * <p>换句话说:<b>控制流在代码里,不在模型里。</b>
 * 下次改成 Agent 版时,你会发现第 ①②③ 步全都消失了 ——
 * 取而代之的是"把这些查询能力交给模型,让它自己决定调哪个、调几次"。
 * 那时候对比着看,区别就一目了然了。
 *
 * <h1>这个类里其实藏着两个知识点</h1>
 * <ol>
 *   <li><b>ChatClient</b> —— Spring AI 调模型的统一入口,第 ⑤ 步用它</li>
 *   <li><b>上下文工程</b> —— 第 ④ 步的 {@link #renderFacts} 才是真正的活儿。
 *       "把数据库里那堆数字翻译成模型能读懂的话"这件事,做得糙做得好,结果差很远</li>
 * </ol>
 */
@Service
public class AiRecommendService {

    private static final Logger log = LoggerFactory.getLogger(AiRecommendService.class);

    /** 最多看几家店。定这个数是因为:每多一家就多一次 HTTP 调用,还多占上下文。 */
    private static final int TOP_N = 5;

    /**
     * 系统提示词(System Prompt)—— 相当于给模型立的"岗位说明书"。
     *
     * <p>注意第 1、2 条:这是在<b>防幻觉</b>。模型天生有"把话说圆"的倾向,
     * 你不明确禁止,它就可能编出一个不存在的地址或优惠券。
     * 但也要知道:<b>提示词只是软约束,拦不住真的想乱来的模型</b>。
     * 真正硬的手段是"根本不给它编造的机会"(比如工具返回什么它就只能用什么),
     * 那是后面 Agent 版要讲的事。
     */
    private static final String SYSTEM_PROMPT = """
            你是一个本地生活推荐助手。用户会给你一批「店铺 + 优惠券」的真实数据,
            请你基于这些数据,用自然、口语化的中文做推荐。

            硬性要求:
            1. 只能使用给你的数据。绝对不要编造店名、地址、人均价格、评分或优惠券。
            2. 数据里没写的,就说"数据里没有",不要猜。
            3. 优先介绍:店名、所在商圈、人均、评分,以及有没有优惠券。
            4. 如果这些店都没有优惠券,就如实说明"这几家目前没有优惠券"。
            5. 控制在 200 字以内,不要说套话。
            """;

    private final HmdpClient hmdpClient;
    private final ChatClient chatClient;

    /**
     * 构造器注入两样东西:
     * <ul>
     *   <li>{@link HmdpClient} —— 我们自己的,负责取数据</li>
     *   <li>{@code ChatClient.Builder} —— Spring AI 自动装配好的。
     *       注意注入的是 <b>Builder</b> 不是 ChatClient 本身 ——
     *       Spring AI 只提供 Builder,由我们自己 {@code build()}。
     *       这样你可以按需 build 出多个配置不同的 ChatClient(比如一个严谨版、一个创意版)</li>
     * </ul>
     */
    public AiRecommendService(HmdpClient hmdpClient, ChatClient.Builder chatClientBuilder) {
        this.hmdpClient = hmdpClient;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 走完五步流程,返回推荐结果。
     *
     * @param keyword 用户搜的关键词
     */
    public RecommendResult recommend(String keyword) {

        // ─────────────────────────────────────────────
        // ① 搜店铺
        // ─────────────────────────────────────────────
        List<ShopView> shops;
        try {
            shops = hmdpClient.searchShopsByName(keyword, 1);
        } catch (Exception e) {
            // 手动启动三个服务(MySQL/Redis/hmdp)很容易漏一个。
            // 与其抛一个看不懂的堆栈,不如直接告诉人怎么办。
            log.error("调用 hmdp 失败", e);
            return new RecommendResult(
                    "调用 hmdp 失败了。请确认:MySQL 在跑、Redis 在跑、hmdp 后端(8081)在跑。"
                            + "原始错误:" + e.getMessage(),
                    "", 0, 0);
        }

        if (shops.isEmpty()) {
            return new RecommendResult("没有找到和「" + keyword + "」相关的店铺。", "", 0, 0);
        }

        // ─────────────────────────────────────────────
        // ② 取前 N 家
        // ─────────────────────────────────────────────
        List<ShopView> top = shops.stream().limit(TOP_N).toList();

        // ─────────────────────────────────────────────
        // ③ 挨个查优惠券
        //    用 LinkedHashMap 而不是 HashMap —— 保持顺序,输出稳定好排查
        // ─────────────────────────────────────────────
        Map<Long, List<VoucherView>> vouchersByShop = new LinkedHashMap<>();
        for (ShopView shop : top) {
            vouchersByShop.put(shop.id(), hmdpClient.listVouchers(shop.id()));
        }

        // ─────────────────────────────────────────────
        // ④ 组装上下文 —— ★ 这一步才是"上下文工程",真正决定输出质量 ★
        // ─────────────────────────────────────────────
        String facts = renderFacts(top, vouchersByShop);

        // 把"模型实际看到的东西"打到日志里。
        // 调试 AI 应用 90% 的时间都花在看这个 —— 模型答得不对,
        // 八成不是你 prompt 写得不好,而是你喂给它的数据本身就是乱的。
        log.debug("喂给模型的上下文:\n{}", facts);

        // ─────────────────────────────────────────────
        // ⑤ 调模型
        // ─────────────────────────────────────────────
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)   // 岗位说明书:你是谁、守什么规矩
                .user(facts + "\n请根据以上数据做推荐。")   // 这次的具体任务
                .call()                  // 同步调用。要流式的话用 .stream()
                .content();              // 取出纯文本结果

        log.debug("模型回答:{}", answer);

        int voucherCount = vouchersByShop.values().stream().mapToInt(List::size).sum();
        return new RecommendResult(answer, facts, top.size(), voucherCount);
    }

    /**
     * 把结构化数据渲染成一段给模型读的文本。★ 这是整个类最值钱的方法 ★
     *
     * <h2>为什么不能直接把 JSON 丢给模型</h2>
     * 能跑,但效果差。原因有两个:
     * <ol>
     *   <li><b>单位会坑死人</b>。库里 avgPrice 是元、payValue 是分,score 是"37 表示 3.7 分"。
     *       直接把原始数字丢过去,模型会一本正经地说"评分 46 分、满 5000 减 4750"。
     *       这类错误在 AI 应用里极其常见,而且看起来特别蠢</li>
     *   <li><b>Token 是钱</b>。JSON 的引号、括号、重复的字段名全是开销。
     *       同一份数据,渲染成紧凑的文本能省一大截 —— 这也是上下文工程的一部分</li>
     * </ol>
     *
     * <p>一句话总结这个方法:<b>把"数据库的样子"翻译成"人话"</b>。
     * 模型是照着人话说话的,你喂数据它就说数据话,你喂人话它才说人话。
     */
    private String renderFacts(List<ShopView> shops, Map<Long, List<VoucherView>> vouchersByShop) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是数据库里的真实数据:\n\n");

        for (ShopView s : shops) {
            sb.append("【店铺】").append(s.name()).append('\n');
            sb.append("  商圈: ").append(orDash(s.area())).append('\n');
            sb.append("  地址: ").append(orDash(s.address())).append('\n');
            // 人均:库里就是"元",直接用
            sb.append("  人均: ").append(s.avgPrice() == null ? "未知" : s.avgPrice() + " 元").append('\n');
            // 评分:库里是 37 这种十分制整数,必须 /10
            sb.append("  评分: ").append(formatScore(s.score())).append('\n');
            sb.append("  营业时间: ").append(orDash(s.openHours())).append('\n');

            List<VoucherView> vouchers = vouchersByShop.getOrDefault(s.id(), List.of());
            if (vouchers.isEmpty()) {
                sb.append("  优惠券: 无\n");
            } else {
                sb.append("  优惠券:\n");
                for (VoucherView v : vouchers) {
                    sb.append("    - ").append(v.title())
                            .append("(花 ").append(fen2yuan(v.payValue()))
                            .append(" 元可抵 ").append(fen2yuan(v.actualValue()))
                            .append(" 元)");
                    if (v.stock() != null) {
                        sb.append(",剩余 ").append(v.stock()).append(" 张");
                    }
                    sb.append('\n');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 分 → 元。
     *
     * <p>库里存的是分(4750 = 47.5 元)。整数元时不显示小数点,免得都变成 "80.00 元" 这种废话。
     */
    private static String fen2yuan(Long fen) {
        if (fen == null) {
            return "?";
        }
        if (fen % 100 == 0) {
            return String.valueOf(fen / 100);
        }
        return String.format("%.2f", fen / 100.0);
    }

    /**
     * 十分制整数 → 分数文本。
     *
     * <p>库里 37 表示 3.7 分,46 表示 4.6 分。直接显示 46 会让模型说出"评分 46 分"。
     * 另外提一句:这个"37 表示 3.7"的约定是我们从数据推断的,不是文档写死的 ——
     * 跑起来后对照 hmdp 前端页面看一眼,如果对不上,改这一行就行。
     */
    private static String formatScore(Integer score) {
        if (score == null || score == 0) {
            return "暂无评分";
        }
        return String.format("%.1f 分", score / 10.0);
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    /**
     * 返回给调用方的结果。
     *
     * <p>注意多带了两个字段:{@code factsSentToModel} 和那些计数。
     * 这不是给用户看的,是<b>给你调试用的</b> —— 让你在浏览器里就能看到
     * "模型到底收到了什么"。AI 应用最反直觉的一点就是:
     * 出问题时你第一件该做的事不是改提示词,而是去看你喂进去的数据长什么样。
     *
     * @param answer           模型生成的推荐语
     * @param factsSentToModel 实际发给模型的那段上下文(第 ④ 步的产物)
     * @param shopCount        参与本次推荐的店铺数
     * @param voucherCount     命中的优惠券总数
     */
    public record RecommendResult(
            String answer,
            String factsSentToModel,
            int shopCount,
            int voucherCount
    ) {
    }
}

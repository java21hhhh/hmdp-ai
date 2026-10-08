package com.hxr.hmdpai.tool;

import com.hxr.hmdpai.dto.ShopView;

/**
 * 工具的<b>返回值</b> —— 一个店铺在模型眼里长什么样。
 *
 * <h1>★ 这个类为什么存在，是今天最重要的一个认知 ★</h1>
 *
 * <p>回想 Workflow 版的 {@code AiRecommendService.renderFacts}：
 * 它负责把"数据库的样子"翻译成"人话"。为什么要有那一步？
 * 因为库里 {@code score=37} 表示 3.7 分、{@code payValue=4750} 表示 47.5 元，
 * 直接把原始数字丢给模型，它会一本正经地说"评分 37 分"。
 *
 * <p><b>现在改成 Agent 版了，{@code renderFacts} 没有了。</b>
 * 那单位换算这件事跑哪儿去了？
 *
 * <p>答案是：<b>搬到了工具的返回值里。</b>
 *
 * <pre>
 *   Workflow 版:  数据库 → [renderFacts 翻译] → 一段文本 → 模型
 *   Agent   版:  数据库 → [工具返回值翻译] → 一段 JSON → 模型
 *                              ↑ 就是这里
 * </pre>
 *
 * <p>想通这一点，你就抓住了 Agent 开发的核心：
 * <b>模型能看见的东西，100% 等于你从工具里返回的东西。</b>
 * 工具返回什么它就只能说什么，一句多的都没有。
 *
 * <p>所以你在这里做的每一个选择——留哪些字段、把数字写成什么格式、
 * 要不要带一段说明文字——都直接影响模型回答的质量。
 * 这就是「上下文工程」在 Agent 里的新位置。
 *
 * <h2>自己动手验证一下这个结论</h2>
 * 把 {@link HmdpTools#searchShopsByName} 的返回类型从 {@code List<ShopBrief>}
 * 改成 {@code List<ShopView>}（也就是原始 DTO，不做任何换算），重新跑一次。
 * 你会亲眼看到模型说出"评分 37 分"这种话。
 * <b>这个实验比看十遍文档都管用。</b>
 *
 * <h2>为什么字段名不叫 avgPrice / score 了</h2>
 * 故意的。字段名本身也是给模型看的信息。
 * 一个叫 {@code avgPrice} 的 Long、和一个叫 {@code avgPrice} 的 String "80 元"，
 * 后者模型绝对不会理解错。这是最低成本、最高收益的上下文工程手段。
 *
 * <h2>为什么 id 偏偏保留了数字类型</h2>
 * 因为 {@code id} 不是给模型"看"的，是给模型"用"的 ——
 * 它要拿这个 id 去调下一个工具 {@link HmdpTools#listVouchers}。
 * 工具参数的类型必须和这里对得上，写成 "1 号店" 这种字符串，模型就没法调了。
 *
 * @param id        店铺 id。<b>必须是数字</b>，模型要拿它去调 listVouchers
 * @param name      店铺名称
 * @param area      所在商圈
 * @param address   详细地址
 * @param avgPrice  人均，已换算成 "80 元" 这种形式（库里就是元，直接用）
 * @param score     评分，已换算成 "3.7 分"（库里是 37 这种十分制整数）
 * @param openHours 营业时间原文，如 "10:00-22:00"
 */
public record ShopBrief(
        Long id,
        String name,
        String area,
        String address,
        String avgPrice,
        String score,
        String openHours
) {

    /**
     * 从数据库来的原始对象，转成"模型能直接读懂"的样子。
     *
     * <p>单位换算的依据是实测过的（不是猜的）：
     * 前端 {@code shop-detail.html} 里写的是 {@code v-model="shop.score/10"}，
     * 喂给 Element UI 的五星组件 {@code el-rate}，所以 37 → 3.7 星；
     * 人均则是前端直接展示 {@code ￥{{shop.avgPrice}}/人}，所以不用换算。
     */
    public static ShopBrief from(ShopView s) {
        return new ShopBrief(
                s.id(),
                s.name(),
                s.area(),
                s.address(),
                s.avgPrice() == null ? "未知" : s.avgPrice() + " 元",
                formatScore(s.score()),
                s.openHours()
        );
    }

    /**
     * 十分制整数 → "3.7 分"。
     *
     * <p>顺带一个细节：{@code score=0} 我们当成"暂无评分"而不是"0.0 分"。
     * 因为库里没评分的店存的就是 0，直接显示 0 分会误导模型和用户。
     * <b>这种"脏数据怎么翻译"的判断，只有你知道，模型不可能自己想到。</b>
     */
    private static String formatScore(Integer score) {
        if (score == null || score == 0) {
            return "暂无评分";
        }
        return String.format("%.1f 分", score / 10.0);
    }
}

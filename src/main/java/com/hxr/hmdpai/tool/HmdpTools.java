package com.hxr.hmdpai.tool;

import com.hxr.hmdpai.client.HmdpClient;
import com.hxr.hmdpai.dto.HmdpResult;
import com.hxr.hmdpai.dto.ShopTypeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ★★★ 这个类就是今天的主角：模型的「手和脚」。★★★
 *
 * <h1>它和 AiRecommendService 的根本区别</h1>
 *
 * <pre>
 * AiRecommendService (Workflow):   我写死"先搜店、再查券"，然后调模型
 * HmdpTools        (Agent)   :   我只提供下面这几个能力，
 *                                什么时候调、调几次、用哪个参数，全由模型决定
 * </pre>
 *
 * <h2>四个工具，分两组</h2>
 * <pre>
 *   精准查询组（模型的"手电筒"）—— 知道要找什么时用
 *     searchShopsByName(keyword)   按店名搜
 *     listVouchers(shopId)         查某家店的券
 *
 *   探索组（模型的"地图"）—— 不知道找什么时用
 *     listShopTypes()              看看平台上有哪些分类
 *     listShopsByType(typeId)      按分类列出店
 * </pre>
 *
 * <p>这四把工具的拆分方式，是今天第二个重要认知：
 * <b>Agent 靠"猜"还是靠"查"，取决于你有没有给它探索类的工具。</b>
 * 只有前两个时，模型搜不到就只能瞎蒙关键词；
 * 加上后两个，它才知道"原来还有这么多我没搜到的店"。
 *
 * <p>注意这个类里<b>没有任何流程</b>。没有 if，没有 for，没有"先 A 后 B"。
 * 每个方法都是一个孤立的、随时可以被调用的能力。
 * <b>流程不在这里 —— 流程在模型的脑子里。</b>
 * 这就是从 Workflow 到 Agent 最关键的思维转变。
 *
 * <h1>原理：模型是怎么"调用"一个 Java 方法的</h1>
 *
 * <p>模型本身只会输出文字，它没有手，碰不到你的数据库。整个过程是这样的：
 *
 * <pre>
 *   ① 你把这张"说明书"发给模型：
 *        - 有个函数叫 searchShopsByName
 *        - 作用：按店名模糊搜索店铺
 *        - 参数：keyword（字符串）
 *        - 返回值：店铺列表
 *
 *   ② 模型看完说明书，决定要用它，于是<b>不输出人话</b>，而是输出一段结构化数据：
 *        {"name":"searchShopsByName","arguments":{"keyword":"火锅"}}
 *
 *   ③ Spring AI 收到这段数据，<b>替你执行</b>下面这个 Java 方法，
 *      把返回值塞回对话历史里
 *
 *   ④ 模型读到返回值，继续决定：还要再查吗？还是可以回答了？
 *
 *   ③④ 会反复循环，直到模型说"我说完了"。这个循环叫 ReAct 循环（Reason + Act）。
 *   <b>你不用写 while</b> —— Spring AI 在 .call() 内部帮你转。
 * </pre>
 *
 * <h1>★ @Tool 的 description 就是 prompt ★</h1>
 *
 * <p>模型选不选这个工具、参数传得对不对，<b>完全靠读 description</b>。
 * 写"查询店铺"和写"按【店铺名称】模糊搜索，不搜分类和地址"是两回事 ——
 * 前者会让模型在用户问"有什么好吃的"时也来调它，然后搜出空列表。
 *
 * <p>一个反直觉的事实：<b>在 Agent 开发里，你花在 description 上的时间，
 * 应该比花在 system prompt 上的时间还多。</b>因为 system prompt 影响"怎么说"，
 * description 影响"做得对不对"。
 *
 * <h1>为什么这个类不是 @Component</h1>
 *
 * <p>因为工具对象<b>不需要是 Spring Bean</b>。{@code .tools(...)} 接受任意 Java 对象，
 * 只要它的方法上有 {@code @Tool} 注解。既然它没有要注入的依赖以外的状态，
 * 那就每次请求 new 一个 —— 这样 {@link #trace} 这个字段天然只属于这一次请求，
 * 不用操心并发和清理，顺手还记录了"模型这一轮到底干了什么"。
 */
public class HmdpTools {

    private static final Logger log = LoggerFactory.getLogger(HmdpTools.class);

    private final HmdpClient hmdpClient;
    private final String token;

    /**
     * ★ 硬确认门 ★ 本次请求带过来的"用户已确认"信号 —— 就是 URL 上的 {@code confirm=10}。
     *
     * <p><b>它可以是 null</b>，那就代表"用户还没点确认"。所以用它之前必须先判空。
     * （别写成 {@code confirmVoucherId.toString()} —— null 会当场炸，而 null 正是这道门最常见的样子。）
     *
     * <p>为什么它和 token 一样是"从请求里带进来的"，而不是模型给的参数？
     * <b>因为一旦变成一个参数，模型就能自己填 —— 这门就白装了。</b>
     */
    private final Long confirmVoucherId;

    /**
     * ★ 硬护栏 ★ 本次请求中，已经真的去抢过的券。
     *
     * <p>能直接放字段、不用管并发，是因为 HmdpTools 是每个请求 new 一个的。
     * 要是哪天它变成了单例，这里就必须上锁 —— 而且护栏会变成"全站所有人只许抢一次"。
     */
    private final Set<Long> triedVoucherIds = new HashSet<>();

    public HmdpTools(HmdpClient hmdpClient, String token, Long confirmVoucherId) {
        this.hmdpClient = hmdpClient;
        this.token = token;
        this.confirmVoucherId = confirmVoucherId;   // 允许为 null：null = 用户还没确认
    }

    /**
     * 记录模型这一轮调了哪些工具、用了什么参数 —— 纯粹给我们人看的。
     *
     * <p>这个列表会跟着返回值一起回到浏览器里。
     * <b>它是理解 Agent 最重要的一个窗口</b>：你在浏览器里就能看到
     * "模型先搜了什么、发现没结果、又换了什么词试"。
     *
     * <p>因为 {@link HmdpTools} 是每个请求 new 一个的，所以这里不用考虑线程安全，
     * 也不会串味。
     */
    private final List<String> trace = new ArrayList<>();



    /** 供 Service 在请求结束后取走调用记录。返回副本，防止外部改坏了。 */
    public List<String> trace() {
        return List.copyOf(trace);
    }

    // ════════════════════════════════════════════════════════════════
    //  工具 1：按店名搜店铺
    // ════════════════════════════════════════════════════════════════

    /**
     * 按关键词搜店铺。
     *
     * <p>{@code @Tool} 的 {@code name} 是<b>模型看到的名字</b>，和 Java 方法名无关
     * （这里故意写成一样，方便你对照）。也可以改成 {@code "find_shops"} 之类，
     * 模型就只会看到那个名字。一般<u>保持和 Java 方法名一致</u>最好维护。
     *
     * <p>{@code description} 里我做了三件事，每一件都有理由：
     * <ol>
     *   <li><b>说清楚它搜的是什么</b>："只匹配店名，不匹配分类、地址、菜品" ——
     *       不写这句，模型很可能拿"美食""杭州"这种词来搜，然后拿到空列表一脸茫然</li>
     *   <li><b>说清楚返回了什么</b>：让模型知道拿到手的是什么，才好决定下一步</li>
     *   <li><b>告诉它怎么接着往下走</b>："返回的 id 可以直接传给 listVouchers" ——
     *       ★ 这句是让模型学会"串工具"的关键 ★。
     *       模型的推理能力没问题，它缺的是"这里有个叫 listVouchers 的工具能用"这个信息</li>
     * </ol>
     *
     * <p><b>注意方法体：只有"查询 + 翻译 + 记日志"，没有任何判断。</b>
     * 没有"如果没搜到就换关键词"——那种逻辑会破坏 Agent 的意义。
     * 搜不到就老老实实返回空列表，让模型自己去面对这个事实、自己去想办法。
     */
    @Tool(name = "searchShopsByName",
            description = """
                    按【店铺名称】模糊搜索店铺。
                    只在店名里找关键词，不搜分类、地址或菜品：搜「火锅」能命中店名里带「火锅」的店，
                    搜「美食」「杭州」这种词一条也搜不到。
                    返回店铺列表，每项包含：id、名称、商圈、地址、人均、评分、营业时间。
                    返回结果里的 id 可以直接传给 listVouchers 查询这家店的优惠券。
                    店铺没有优惠券时返回空列表。
                    每页最多 10 家，用 current 翻页（从 1 开始，默认第一页）"""
    )
    public List<ShopBrief> searchShopsByName(
            @ToolParam(description = "店铺名称的关键词，比如「火锅」「茶餐厅」「海底捞」。只匹配店名")
            String keyword,
            @ToolParam(required = false, description = "页码，从 1 开始，默认 1。每页 10 家")
            Integer current) {

        int page = (current == null || current < 1) ? 1 : current;
        trace.add("searchShopsByName(\"" + keyword + "\", 第" + page + "页)");
        log.info("[工具] >>> 模型调用 searchShopsByName(keyword={}, current={})", keyword, page);

        List<ShopBrief> result = hmdpClient.searchShopsByName(keyword, page)
                .stream()
                .map(ShopBrief::from)
                .toList();

        log.info("[工具] <<< searchShopsByName 命中 {} 家店", result.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  工具 2：查店铺的优惠券
    // ════════════════════════════════════════════════════════════════

    /**
     * 查某家店的优惠券。
     *
     * <p>参数写的是 {@code Long shopId}，模型就知道这里必须填<b>数字</b>。
     * 如果写成 {@code String shopName}，模型就会传店名进来 ——
     * 那样的话你就得自己写"按名字反查 id"的逻辑，还得处理重名。
     * <b>让参数类型本身承担约束，比在 description 里写十遍"请填数字"管用。</b>
     *
     * <p>description 里特意点明"不是店名"：因为 {@code id} 和 {@code shopId} 这两个词
     * 挨得很近，模型偶尔会把上一步拿到的店名塞进来。这种"模型传错参数"是 Agent 最常见的
     * 故障之一，而 description 是性价比最高的防线。
     */
    @Tool(name = "listVouchers",
            description = """
                    查询某个店铺当前在售的优惠券。
                    参数必须是【店铺 id】（数字），来自 searchShopsByName 返回结果里的 id 字段，
                    不是店铺名称。
                    返回优惠券列表，每项包含：券的标题、要花多少钱、能抵多少钱、
                    是普通券还是秒杀券、秒杀券的剩余库存。
                    这家店没有优惠券时返回空列表。""")
    public List<VoucherBrief> listVouchers(
            @ToolParam(description = "店铺 id（数字），来自 searchShopsByName 结果里的 id 字段")
            Long shopId) {

        trace.add("listVouchers(" + shopId + ")");
        log.info("[工具] >>> 模型调用 listVouchers(shopId={})", shopId);

        List<VoucherBrief> result = hmdpClient.listVouchers(shopId)
                .stream()
                .map(VoucherBrief::from)
                .toList();

        log.info("[工具] <<< listVouchers 命中 {} 张券", result.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  工具 3：看地图 —— 平台上有哪些分类
    // ════════════════════════════════════════════════════════════════

    /**
     * 列出所有店铺分类。
     *
     * <p><b>★ 这个工具就是"从猜变成查"的那把钥匙。★</b>
     *
     * <p>没有它的时候，模型面对"火锅没券"这个困境，唯一的出路是<b>编关键词</b>——
     * 它编出了「茶餐厅」「烤肉」「川菜」「烧烤」「日料」，撞中「茶餐厅」纯属运气。
     *
     * <p>有了它，模型第一次能看到"原来平台上还有 KTV、丽人·美发、美容SPA…"。
     * 它虽然不能从这里直接读出"哪家店有券"，但它能顺着「美食」这个分类
     * 有条理地往下查，而不是在黑屋子里乱摸。
     *
     * <p><b>description 里那句"分类名不能拿去调 searchShopsByName"是必需的</b>：
     * 分类叫「美食」，可没有哪家店的名字里带"美食"两个字。
     * 不写这句，模型几乎必然会拿「美食」去搜店名，然后拿到空列表、
     * 浪费一次调用、还可能得出"平台没有美食店"这种荒谬结论。
     *
     * <p>这就是 tool description 的本质：<b>把你脑子里"这工具不能这么用"的常识，
     * 用文字补给模型。</b>它不会举一反三，你得喂给它。
     */
    @Tool(name = "listShopTypes",
            description = """
                    列出平台上所有的店铺分类，比如「美食」「KTV」「丽人·美发」「美容SPA」等。
                    返回每个分类的 id 和名称。
                    注意：分类名称不能拿去调 searchShopsByName —— 没有哪家店的名字里带「美食」两个字，
                    那样搜只会得到空列表。分类 id 请传给 listShopsByType 使用。
                    当你想知道"平台上一共有哪些类型的店"时，用这个工具。""")
    public List<ShopTypeView> listShopTypes() {
        trace.add("listShopTypes()");
        log.info("[工具] >>> 模型调用 listShopTypes()");

        List<ShopTypeView> result = hmdpClient.listShopTypes();

        log.info("[工具] <<< listShopTypes 返回 {} 个分类", result.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  工具 4：按分类列店 —— 顺着地图走
    // ════════════════════════════════════════════════════════════════

    /**
     * 按分类分页列出店铺，返回值也是 {@link ShopBrief}，和 {@link #searchShopsByName} 一样。
     *
     * <p><b>为什么返回值类型要和搜店那个保持一致？</b>两个理由：
     * <ol>
     *   <li>模型的"世界观"要统一。它从两个渠道拿到同样形状的店铺数据，
     *       后续处理（比如决定查哪几家的券）就不用分情况</li>
     *   <li>如果这里返回带一堆原始字段的东西，模型会看到两种不同的店铺格式，
     *       凭空增加它的困惑。**工具的返回值应该长得像同一套东西。**</li>
     * </ol>
     *
     * <h2>关于那个 required = false 的 current 参数</h2>
     *
     * <p>这是本次改动里最微妙的一处设计。因为 hmdp 那个接口每页只给 5 家
     * （见 {@code HmdpClient.listShopsByType} 的注释），「美食」一共 9 家，
     * <b>不翻页就会漏掉 4 家。</b>
     *
     * <p>三种做法，我们选了第三种：
     * <ul>
     *   <li>❌ 不让模型翻页，我们自己把 1~3 页全拉回来拼成一个列表 —— 省事，
     *       但模型永远学不会"数据是分页的"，将来接真接口会出大问题</li>
     *   <li>❌ 让 current 必填 —— 模型每次都得编一个页码，容易瞎填</li>
     *   <li>✅ <b>可选参数 + 描述里写明"每页 5 家，没找全可以翻页"</b> ——
     *       模型自己决定翻不翻。这是最像真实开发的方案</li>
     * </ul>
     *
     * <p>方法体里那个 {@code current == null ? 1 : current} <b>不能省</b>：
     * 模型不传这个参数时，Spring AI 传进来的就是 {@code null}，
     * 直接拆箱成 {@code int} 会抛 NullPointerException。
     * <b>工具的参数永远要假设模型可能不传、传错、传 null。</b>
     */
    @Tool(name = "listShopsByType",
            description = """
                    按【店铺分类】分页列出店铺，返回的字段和 searchShopsByName 完全一样（含 id）。
                    参数 typeId 必须是分类 id（数字），来自 listShopTypes 返回结果里的 id 字段。
                    每页最多只返回 5 家，用 current 翻页（从 1 开始，默认第一页）。
                    如果这一页没看到想要的店，可以翻到下一页继续看。
                    典型用法：某个关键词搜不到合适的店，但你不想就此放弃 ——
                    先调 listShopTypes 找到对应的大类，再用这个工具把该分类下的店挨个看一遍。""")
    public List<ShopBrief> listShopsByType(
            @ToolParam(description = "分类 id（数字），来自 listShopTypes 结果里的 id 字段")
            Integer typeId,
            @ToolParam(required = false, description = "页码，从 1 开始，默认 1。每页 5 家")
            Integer current) {

        int page = (current == null || current < 1) ? 1 : current;
        trace.add("listShopsByType(typeId=" + typeId + ", 第" + page + "页)");
        log.info("[工具] >>> 模型调用 listShopsByType(typeId={}, current={})", typeId, page);

        List<ShopBrief> result = hmdpClient.listShopsByType(typeId, page)
                .stream()
                .map(ShopBrief::from)
                .toList();

        log.info("[工具] <<< listShopsByType 命中 {} 家店", result.size());
        return result;
    }

    @Tool(name = "seckillVoucher",
            description = """
                      对指定的优惠券秒杀下单，
                      voucherId 必须来自【本次请求中】listVouchers 刚返回的结果里
                      kind=秒杀券 的那一项的 id 字段。
                      ⚠️ 不要使用你记忆里或印象中的数字 —— 你不是每次都能看到历史里的工具返回值，
                      凭印象填的 id 一定是错的，会让你白跑一趟。
                      如果你手上没有本次请求查到的 id，先调用 searchShopsByName 找到店铺，
                      再用 listVouchers(shopId) 取券列表，从中读出 id，然后再调用本工具。
                      当用户明确表达要抢购、下单某张券时使用，
                      kind=普通券 的券不支持秒杀，不要拿它的 id 调用本工具
                      当用户只是浏览询问时不要调用，
                      成功时返回订单号，失败时返回失败原因，
                      失败代表确定性失败（如库存不足、重复下单、未登录），不要重试，直接把失败原因告诉用户。
                      这是写操作，会真实下单扣减库存
                      """)
    public String seckillVoucher(
            @ToolParam(description = "秒杀券的 id（数字）。必须是【本次请求中】listVouchers 刚刚返回的 id —— "
                    + "不要填你记忆里的数字，那些可能已经过期或根本不存在")
            Long voucherId) {

        // ★ 第一道：硬确认门 ——「你有权做这件事吗？」
        //   confirmVoucherId 是【用户】在 URL 上带过来的，模型看不见、更编不出来。
        //   两个都要判：① null = 用户压根没确认   ② 对不上 = 用户确认的是别的券
        if (confirmVoucherId == null || !confirmVoucherId.equals(voucherId)) {
            log.warn("[工具] 确认门拦截：模型报 voucherId={}，用户确认的是 confirm={}",
                    voucherId, confirmVoucherId);
            trace.add("seckillVoucher(" + voucherId + ") 缺少用户确认");

            if (confirmVoucherId == null) {
                // 用户还没点确认。别告诉他任何券的信息 —— 他还得先去问用户。
                return "未获用户确认：下单是不可撤销的写操作，必须由用户本人确认。"
                        + "请先告诉用户你打算抢哪张券（券名和价格），等他明确同意后再试。";
            }

            // 用户确实确认了，是【模型报错了券】。
            // ★ 关键：把正确的 id 直接告诉它 ★
            // 不然它下一轮还是只能猜（它猜过 0，也猜过 -1），会一直卡在这道门上。
            //
            // ⚠️ 指路时必须点名"参数从哪来"。上一版只说了"调用 listVouchers"，
            //    它拿着 -1 就去查了 —— 因为 listVouchers 要的是 shopId，它不知道。
            return "你报的 voucherId=" + voucherId + " 和用户确认的不是同一张券。"
                    + "用户确认的是 voucherId=" + confirmVoucherId + "。"
                    + "如果你要核实这张券存不存在、是不是秒杀券，请按顺序查："
                    + "先 searchShopsByName 找到店铺、拿到 shopId，"
                    + "再 listVouchers(shopId) 取券列表（注意：这里传的是【店铺 id】，不是券 id），"
                    + "从返回结果里读出券的 id，然后重新调用本工具。";
        }

        // ★ 第二道：硬护栏 ——「你不是已经做过了吗？」
        //   同一个券 id，本次请求里只允许真的去抢一次
        if (!triedVoucherIds.add(voucherId)) {
            log.warn("[工具] 模型试图重复抢券 voucherId={}，已被硬护栏拦下", voucherId);
            trace.add("seckillVoucher(" + voucherId + ") 被硬护栏拦截");
            return "本次对话中你已经抢过这张券了，不要重复抢购。";
        }

        trace.add("seckillVoucher(" + voucherId + ")");
        log.info("[工具] >>> 模型调用 seckillVoucher(voucherId={})", voucherId);

        HmdpResult<Long> r = hmdpClient.seckillVoucher(voucherId, token);

        // ★ 看清楚这行：HTTP 200 也可能是业务失败，必须看 success 字段
        if (Boolean.TRUE.equals(r.success())) {
            log.info("[工具] <<< 秒杀成功，订单号 {}", r.data());
            return "抢购成功，订单号 " + r.data();
        }
        log.info("[工具] <<< 秒杀失败：{}", r.errorMsg());
        return "抢购失败，原因：" + r.errorMsg();
    }
}

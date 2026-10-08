package com.hxr.hmdpai.tool;

import com.hxr.hmdpai.dto.VoucherView;

/**
 * 优惠券在模型眼里的样子。设计思路和 {@link ShopBrief} 完全一样，
 * 看那个类的注释就够了，这里只说不一样的地方。
 *
 * <h2>⚠️ 这里的单位是「分」，和 ShopBrief 的「元」不一样</h2>
 * 库里 {@code payValue=4750, actualValue=5000} 表示"花 47.5 元抵 50 元"。
 *
 * <p>这个"分"是怎么确认的？<b>不用查文档，数据自己就说了</b>：
 * 那张券的标题字面写着「50元代金券」，而 actualValue 是 5000 ——
 * 只有除以 100 才等于 50。要是按元算，就变成"花 5000 元抵 5000 元的 50 元券"，荒谬。
 *
 * <p><b>注意</b>：同一个数据库里，{@code tb_shop.avgPrice} 是元、
 * {@code tb_voucher.payValue} 是分。这不是我们写错了，是 hmdp 项目自带的历史遗留。
 * 这种"同一个库里两种单位"的情况在真实项目里非常常见，
 * 而 AI 应用对它的容忍度为零 —— 模型不会帮你发现单位不对，它只会一本正经地把错的说出来。
 *
 * @param id         券 id
 * @param shopId     属于哪家店。模型靠它把券和店对上号
 * @param title      券的标题原文
 * @param payYuan    要花多少钱，已换算成 "47.50 元"
 * @param actualYuan 能抵多少钱，已换算成 "50 元"
 * @param kind       "普通券" / "秒杀券"。这是把数字类型翻译成人话
 * @param stock      库存，只有秒杀券才有；普通券是 null
 */
public record VoucherBrief(
        Long id,
        Long shopId,
        String title,
        String payYuan,
        String actualYuan,
        String kind,
        Integer stock
) {

    public static VoucherBrief from(VoucherView v) {
        return new VoucherBrief(
                v.id(),
                v.shopId(),
                v.title(),
                fen2yuan(v.payValue()),
                fen2yuan(v.actualValue()),
                kindOf(v.type()),
                v.stock()
        );
    }

    /**
     * 分 → "47.5 元"。
     *
     * <p>整数元时不补小数点，免得满屏 "80.00 元"、"50.00 元" 这种废话 ——
     * 每个字符都是要花钱的 token。
     */
    private static String fen2yuan(Long fen) {
        if (fen == null) {
            return "未知";
        }
        if (fen % 100 == 0) {
            return (fen / 100) + " 元";
        }
        return String.format("%.2f 元", fen / 100.0);
    }

    /**
     * 数字类型 → 人话。
     *
     * <p>为什么不直接把 {@code type: 1} 丢给模型？因为它得先知道"1 代表秒杀券"才能理解。
     * 你在这里翻译一次，模型每次调用就少猜一次。这就叫把知识<b>前置</b>到工具里，
     * 而不是指望模型记住。
     *
     * <p>顺带一句：秒杀券和普通券的差别，下一轮做"秒杀护栏"时会变得非常重要 ——
     * 秒杀券会走 {@code /voucher-order/seckill} 那条真实的抢购链路（有 Redisson 锁、
     * 一人一单、Redis Stream 异步下单），而普通券不会。现在先把"它是哪一种"告诉模型。
     */
    private static String kindOf(Integer type) {
        if (type == null) {
            return "未知";
        }
        return type == 1 ? "秒杀券" : "普通券";
    }
}

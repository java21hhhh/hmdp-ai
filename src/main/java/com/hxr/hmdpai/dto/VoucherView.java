package com.hxr.hmdpai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 优惠券信息。
 *
 * <h2>⚠️ 单位陷阱(已实测)</h2>
 * {@code payValue} 和 {@code actualValue} 单位是<b>分</b>。
 * 库里实测一条:title="50元代金券", payValue=4750, actualValue=5000
 * → 花 47.5 元买 50 元的券。显示时必须除以 100。
 *
 * <p>注意这和 {@link ShopView#avgPrice()} 的单位<b>不一样</b> —— 那边是元、这边是分。
 * 同一个项目里两种单位混着,是 hmdp 自带的历史遗留,不是我们写错了。
 * 这种坑不处理,模型就会一本正经地说"满 5000 减 4750"。
 *
 * <h2>为什么没有 beginTime / endTime</h2>
 * 这两个字段来自 tb_seckill_voucher 的联表查询,是 LocalDateTime。
 * 跨进程传日期格式很容易对不上(数组 / ISO 字符串 / 时间戳都有可能),
 * 而推荐场景用不到精确到秒的起止时间,所以干脆不收。
 * 需要的话再加 —— 但这属于"先做能用,再做好"。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VoucherView(
        Long id,
        Long shopId,
        String title,       // 如 "50元代金券"
        String subTitle,    // 如 "周一至周日均可使用"
        String rules,       // 使用规则,含换行
        Long payValue,      // 单位:分,支付金额
        Long actualValue,   // 单位:分,抵扣金额
        Integer type,       // 0=普通券, 1=秒杀券
        Integer status,     // 1=上架
        Integer stock       // 秒杀券库存;普通券为 null
) {
}

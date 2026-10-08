package com.hxr.hmdpai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 商户信息 —— 只挑我们喂给模型用得上的字段。
 *
 * <h2>为什么不直接用 hmdp 的 Shop 实体</h2>
 * 两个原因:
 * <ol>
 *   <li><b>跨进程了</b>。hmdp 的 Shop 是另一个项目里的类,我们根本 import 不到,
 *       只能自己定义一个"接收用的壳"。</li>
 *   <li><b>就算能 import 也不该用</b>。那个实体带着 images(一长串图片 URL)、
 *       坐标 x/y、createTime 之类的字段,全塞进 prompt 只会白烧 token、干扰模型。
 *       只留有用的,是上下文工程最基本的动作。</li>
 * </ol>
 *
 * <h2>⚠️ 单位陷阱(已实测)</h2>
 * <ul>
 *   <li>{@code avgPrice} 单位是<b>元</b>(库里实测 80、85、61),直接显示</li>
 *   <li>{@code score} 是<b>十分制整数</b>(实测 37、46、47 → 应显示为 3.7、4.6、4.7 分),
 *       必须除以 10。不处理的话模型会说出"评分 46 分"这种鬼话。</li>
 * </ul>
 * 单位换算在 {@code AiRecommendService} 里做,不在这里 —— 这里保持"数据库什么样就什么样"。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShopView(
        Long id,
        String name,
        Long typeId,
        String area,        // 商圈,如 "大关"、"拱宸桥/上塘"
        String address,
        Long avgPrice,      // 单位:元
        Integer sold,       // 销量
        Integer comments,   // 评论数
        Integer score,      // 十分制整数,37 表示 3.7 分
        String openHours
) {
}

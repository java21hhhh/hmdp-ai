package com.hxr.hmdpai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 店铺分类（就是 hmdp 首页那一排图标：美食、KTV、丽人·美发…）。
 *
 * <h2>为什么这个小小的 DTO 很重要</h2>
 * 它是 Agent 的<b>「地图」</b>。
 *
 * <p>只有 {@code searchShopsByName} 的时候，模型是在黑屋子里摸关键词 ——
 * 它不知道这世上有什么店，只能凭训练语料里"中文里人们一般这么叫"来猜。
 * 猜「茶餐厅」撞上了是运气，猜「日料」就一无所获。
 *
 * <p>有了分类列表，模型才第一次<b>知道"原来还有这些类别"</b>，
 * 才能从"猜"变成"顺着地图查"。
 *
 * <p>注意：分类**不等于**关键词。分类是"美食""KTV"这种粗粒度的大类，
 * 搜店名时拿「美食」去搜一家都搜不到（没有店名里带"美食"两个字）。
 * 这个区别我在工具的 description 里写清楚了 ——
 * 不写清楚，模型一定会拿分类名去调 searchShopsByName。
 *
 * @param id   分类 id。<b>必须保留数字类型</b>，模型要拿它去调 listShopsByType
 * @param name 分类名称，如「美食」
 * @param sort 排序号，原样带过来。模型一般用不到，但留着不费什么 token
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShopTypeView(
        Long id,
        String name,
        Integer sort
) {
}

package com.hxr.hmdpai.client;

import com.hxr.hmdpai.dto.HmdpResult;
import com.hxr.hmdpai.dto.ShopTypeView;
import com.hxr.hmdpai.dto.ShopView;
import com.hxr.hmdpai.dto.VoucherView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * 把 hmdp 的 HTTP 接口封装成 Java 方法。
 *
 * <p>这一层很薄,但很有用:上层(Service)只看到
 * {@code searchShopsByName("火锅")} 这样的方法,不需要知道背后是 HTTP、是哪个端口、
 * 返回的 JSON 长什么样。以后 hmdp 的接口改了,只改这一个文件。
 *
 * <h2>为什么这两个接口不需要登录 token</h2>
 * hmdp 的 {@code MvcConfig} 里,LoginInterceptor 的排除名单包含
 * {@code /shop/**} 和 {@code /voucher/**},所以查询类接口是公开的。
 * 但 {@code /voucher-order/seckill/**}(下单)不在排除名单里 ——
 * 那是写操作,必须登录。以后做 Agent 版要用到下单时,这里就得处理 token 了。
 */
@Component
public class HmdpClient {

    private static final Logger log = LoggerFactory.getLogger(HmdpClient.class);

    private final RestClient restClient;

    public HmdpClient(@Qualifier("hmdpRestClient") RestClient restClient) {
        this.restClient = restClient;
    }


    /**
     * 按名字关键词搜商户。对应 hmdp 的 {@code GET /shop/of/name}。
     *
     * @param keyword 关键词,比如 "火锅"
     * @param current 页码,从 1 开始
     * @return 匹配的商户;失败或没匹配到返回空列表(不返回 null,调用方少写判空)
     */
    public List<ShopView> searchShopsByName(String keyword, int current) {
        // ParameterizedTypeReference 是为了让 Jackson 知道要还原成
        // HmdpResult<List<ShopView>> 这个具体类型。
        // 泛型在运行时会被擦除,不写这行的话 Jackson 只能还原成 LinkedHashMap。
        HmdpResult<List<ShopView>> result = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/shop/of/name")
                        .queryParam("name", keyword)
                        .queryParam("current", current)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<HmdpResult<List<ShopView>>>() {
                });

        if (result == null || !result.hasData()) {
            log.debug("搜商户没拿到数据: keyword={}, result={}", keyword, result);
            return List.of();
        }
        log.debug("搜商户 keyword={} 命中 {} 家", keyword, result.data().size());
        return result.data();
    }

    /**
     * 列出所有店铺分类。对应 hmdp 的 {@code GET /shop-type/list}。
     *
     * <p>这个接口在 hmdp 那边走的是 Redis 缓存（第 2 章的店铺类型缓存），
     * 所以很快，还不会打到数据库。Agent 多调几次也没什么代价 ——
     * 这也是为什么我们敢把"看地图"做成一个工具。
     *
     * @return 全部分类，按 sort 排好序的
     */
    public List<ShopTypeView> listShopTypes() {
        HmdpResult<List<ShopTypeView>> result = restClient.get()
                .uri("/shop-type/list")
                .retrieve()
                .body(new ParameterizedTypeReference<HmdpResult<List<ShopTypeView>>>() {
                });

        if (result == null || !result.hasData()) {
            log.debug("拿分类列表失败: result={}", result);
            return List.of();
        }
        return result.data();
    }

    /**
     * 按分类分页列出店铺。对应 hmdp 的 {@code GET /shop/of/type}。
     *
     * <p>⚠️ <b>注意每页只有 5 家</b>。这个数不是我们定的 ——
     * hmdp 的 {@code ShopController.queryShopByType} 里写死了
     * {@code new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE)}，
     * 而 {@code DEFAULT_PAGE_SIZE = 5}（搜店名那个接口用的才是 10）。
     *
     * <p>这个"每页几家由对方接口决定、我们只能接受"的情况，
     * 是跨服务调用里最常见的摩擦之一。解决办法就是把它<b>写进工具描述里告诉模型</b>，
     * 让它知道"没找全可以翻页"，而不是傻乎乎地以为这个分类下只有 5 家店。
     *
     * @param typeId  分类 id，来自 {@link #listShopTypes()}
     * @param current 页码，从 1 开始
     */
    public List<ShopView> listShopsByType(Integer typeId, int current) {
        HmdpResult<List<ShopView>> result = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/shop/of/type")
                        .queryParam("typeId", typeId)
                        .queryParam("current", current)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<HmdpResult<List<ShopView>>>() {
                });

        if (result == null || !result.hasData()) {
            log.debug("按分类列店铺没拿到数据: typeId={}, current={}", typeId, current);
            return List.of();
        }
        log.debug("按分类列店铺 typeId={} 第 {} 页命中 {} 家", typeId, current, result.data().size());
        return result.data();
    }

    /**
     * 查某个商户的优惠券。对应 hmdp 的 {@code GET /voucher/list/{shopId}}。
     *
     * @param shopId 商户 id
     * @return 该商户的优惠券;没有券返回空列表
     */
    public List<VoucherView> listVouchers(Long shopId) {
        HmdpResult<List<VoucherView>> result = restClient.get()
                .uri("/voucher/list/{shopId}", shopId)
                .retrieve()
                .body(new ParameterizedTypeReference<HmdpResult<List<VoucherView>>>() {
                });

        if (result == null || !result.hasData()) {
            return List.of();
        }
        return result.data();
    }
    /**
     * 秒杀下单。对应 hmdp 的 {@code POST /voucher-order/seckill/{voucherId}}。
     *
     * <p>⚠️ 这是本项目第一个【写】接口 —— 前面那些全是 GET。
     * 而且它必须带 token：这个路径不在 MvcConfig 的排除名单里。
     *
     * @param voucherId 秒杀券 id（只有 type=1 的券能秒杀，这里是 10）
     * @param token     用户登录凭证，要放进 authorization 请求头
     * @return hmdp 的原始返回壳。注意业务失败也是 HTTP 200，
     *         失败原因在 errorMsg 里 —— 不看 success 字段就会漏
     */
    public HmdpResult<Long> seckillVoucher(Long voucherId, String token) {
        return restClient.post()                      // ← 空 1
                .uri("/voucher-order/seckill/{id}", voucherId)
                .header("authorization",token)               // ← 空 2、空 3
                .retrieve()
                .body(new ParameterizedTypeReference<HmdpResult<Long>>() {
                });
    }
}

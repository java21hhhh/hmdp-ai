package com.hxr.hmdpai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * hmdp 所有接口的统一返回壳,对应 hmdp 里的 {@code com.hmdp.dto.Result}。
 *
 * <p>它的 JSON 长这样:
 * <pre>
 * { "success": true, "errorMsg": null, "data": [ ... ], "total": null }
 * </pre>
 *
 * <p>用泛型是为了同一个壳能装两种数据:
 * {@code HmdpResult<List<ShopView>>} 和 {@code HmdpResult<List<VoucherView>>}。
 *
 * @param success  业务是否成功(注意:HTTP 200 不代表业务成功,要看这个字段)
 * @param errorMsg 失败原因,成功时为 null
 * @param data     真正的数据
 * @param total    分页总数,我们这个场景用不到
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HmdpResult<T>(
        Boolean success,
        String errorMsg,
        T data,
        Long total
) {
    /** 业务成功且确实带了数据才算可用。 */
    public boolean hasData() {
        return Boolean.TRUE.equals(success) && data != null;
    }
}

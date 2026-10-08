package com.hxr.hmdpai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ★ 门卫 ★ —— 每个请求进来，先查钥匙，再放行。
 *
 * <h1>为什么需要它</h1>
 * 这个服务是<b>烧钱</b>的：每调一次 {@code /ai/agent}，背后就是
 * 一次到十几次 DeepSeek 的请求。之前它完全公开 —— 谁扫到 8082 端口，
 * 谁就能免费刷你的额度。
 *
 * <h1>和 hmdp 的 LoginInterceptor 是什么关系</h1>
 * <p>同一类东西，但解决的是<b>不同的问题</b>：
 * <ul>
 *   <li>hmdp 的 {@code LoginInterceptor} 管的是「<b>你是哪个用户</b>」——
 *       要先知道你是谁，才能查你的订单（所以它还得查 Redis 拿 UserHolder）</li>
 *   <li>这个 filter 管的是「<b>你是不是自己人</b>」—— 不在乎你是谁，
 *       只在乎你手上有没有那把钥匙（所以它只比一个字符串，不查库不查缓存）</li>
 * </ul>
 * 前者叫<b>认证</b>（Authentication，你是谁），后者叫<b>鉴权</b>（Authorization，你能干什么）。
 * 生产系统里这两层永远是分开的。
 *
 * <h1>为什么用 Filter 而不是 Interceptor</h1>
 * Filter 属于 Servlet 容器（Tomcat），跑在所有 Spring MVC 逻辑<b>之前</b>；
 * Interceptor 属于 Spring MVC，跑在"已经匹配到哪个 Controller"之后。
 * 一句话：<b>要让请求根本进不来，用 Filter；要在 Controller 前后做手脚，用 Interceptor。</b>
 * 这里我们要的是"根本进不来"。
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private static final String HEADER_NAME = "X-Api-Key";

    private final String apiKey;

    public ApiKeyFilter(@Value("${hmdp-ai.api-key:}") String apiKey) {
        // ★ 故意"启动即失败" ★
        //
        // 没配 key 的话，一个"永远拒绝所有人"的服务是没法用的 ——
        // 但你更不想得到的是一个"永远放行所有人"的服务，而且你还以为它受保护。
        // 所以宁可现在起不来，让你当场看见。
        //
        // 这和之前 DEEPSEEK_API_KEY 占位符那个坑是同一个道理：
        //   ★ 配置错了，要在"启动时"炸，不要等到"第一次真跑"才炸。★
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("""
                    hmdp-ai.api-key 没配置。
                    请先在启动脚本里设置环境变量 HMDP_AI_API_KEY（随便一串够长的随机字符），再启动。
                    这个服务每调一次都烧 DeepSeek 的钱，不允许裸奔。""");
        }
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String provided = request.getHeader(HEADER_NAME);

        if (!apiKey.equals(provided)) {
            log.warn("[门卫] 拒绝访问 {} {} —— 头 X-Api-Key={}",
                    request.getMethod(), request.getRequestURI(),
                    provided == null ? "(没带)" : "(不对)");
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 拒绝时返回什么，也是有讲究的。
     *
     * <p>用 <b>401</b>（Unauthorized）而不是 403、更不是 200：
     * HTTP 状态码是给<b>调用方程序</b>看的，它得能凭状态码判断"我是不是该换个 key 重试"。
     *
     * <p>body 用 JSON 而不是空白页：和 hmdp 的返回格式保持一致，
     * 这样前端（以及我们的测试脚本）不用为"出错"单独写一套解析。
     */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"success":false,"errorMsg":"未授权：请求头 X-Api-Key 缺失或错误"}""");
    }
}

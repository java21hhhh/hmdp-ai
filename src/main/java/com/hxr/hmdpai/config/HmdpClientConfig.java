package com.hxr.hmdpai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 配一个专门用来调 hmdp 的 HTTP 客户端。
 *
 * <h2>RestClient 是什么</h2>
 * Spring 6 引入的同步 HTTP 客户端,是 {@code RestTemplate} 的正式接班人
 * (RestTemplate 从 Spring 5 起就进入维护模式了,能用但不再加新特性)。
 * 用法上都是"链式调用 + 自动 JSON 转换",你会 RestTemplate 的话基本无缝。
 *
 * <h2>为什么要配成 Bean,而不是每次 new</h2>
 * 因为 base-url 要写一次就好。配成 Bean 之后,{@code HmdpClient} 只要注入进来,
 * 直接写 {@code .uri("/shop/of/name")} 这种相对路径,不用每处都拼域名。
 * 以后地址变了(比如部署到服务器),只改 application.yml 一行。
 *
 * <h2>{@code @Value} 和 {@code @ConfigurationProperties} 怎么选</h2>
 * 这里只有一个属性,{@code @Value} 够了。要是配置项多起来(比如十几个),
 * 就该换成 {@code @ConfigurationProperties(prefix = "hmdp")} + 一个配置类。
 */
@Configuration
public class HmdpClientConfig {

    /**
     * 注意 Bean 名字故意叫 {@code hmdpRestClient} 而不是默认的
     * {@code restClient} —— 免得和 Spring AI 自己内部的 HTTP 客户端撞名。
     */
    @Bean
    public RestClient hmdpRestClient(@Value("${hmdp.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}

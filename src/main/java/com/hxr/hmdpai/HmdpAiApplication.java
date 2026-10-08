package com.hxr.hmdpai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * hmdp-ai 启动类。
 *
 * <p>这是一个<b>独立于 hmdp 的服务</b>,不是 hmdp 的模块。原因:
 * hmdp 是 Spring Boot 2.7.4,而 Spring AI 2.0 要求 Spring Boot 4.1,
 * 两者不兼容(光是 javax → jakarta 的迁移就能让课程代码大面积报错)。
 * 所以让它单独跑,通过 HTTP 调 hmdp 的接口。
 *
 * <p>启动顺序:MySQL / Redis → hmdp(8081) → 本服务(8082)。
 */
@SpringBootApplication
public class HmdpAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(HmdpAiApplication.class, args);
    }
}

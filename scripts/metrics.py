# -*- coding: utf-8 -*-
"""
仪表盘 —— 从 actuator 端点读聚合指标
=====================================

用法:
    python3.12 metrics.py

它做的事就是给你刚才加的 actuator 端点发一个带 X-Api-Key 的请求，把 JSON 打出来。
为什么不用浏览器直接开？
    因为 X-Api-Key 是个自定义请求头，浏览器地址栏发不了 ——
    这正是"自定义头"和"URL 参数"的区别：头是给程序用的，参数是给人点着玩的。
    （所以你的 ApiKeyFilter 用的是头，不是 ?key=xxx —— 后者会被浏览器历史、
      Nginx 访问日志、Referer 头到处记录。）

⚠️ 这个脚本读的是【累计值】，不是单次请求的账。
    想看单次请求的账，看 hmdp-ai 的启动日志（那里有 [账本] 开头的行）。
"""

import json
import os
import sys
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "eval_simple"))
import runner  # noqa: E402  —— 借它读好的 apikey.txt

BASE = "http://localhost:8082"


def get(path):
    request = urllib.request.Request(BASE + path)
    request.add_header("X-Api-Key", runner.API_KEY)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    try:
        names = get("/actuator/metrics").get("names", [])
    except Exception as e:
        print(f"连不上 {BASE} —— 服务起了吗？")
        print(f"原始错误: {e}")
        return

    print("=== 所有 AI 相关指标 ===")
    for name in names:
        if name.startswith("gen_ai") or "chat" in name.lower():
            print("  *", name)
    print()

    # token 用量：按 gen_ai.token.type 拆开一个个查，比看一个混在一起的合计清楚。
    # （不带 tag 直接查，拿到的是【所有标签加起来】的数，input 和 output 混在一起。）
    if "gen_ai.client.token.usage" in names:
        print("=== gen_ai.client.token.usage（累计 token 数）===")
        for token_type in ("input", "output", "total"):
            path = ("/actuator/metrics/gen_ai.client.token.usage"
                    "?tag=gen_ai.token.type:" + token_type)
            data = get(path)
            value = 0.0
            for m in data.get("measurements", []):
                if m["statistic"] == "COUNT":
                    value = m["value"]
            print(f"  {token_type:<8} = {value:.0f}")
        print()
    else:
        print("=== gen_ai.client.token.usage ===")
        print("  还没有数据。先跑一次 /ai/agent 再来 —— 指标是"
              "「有调用才有数据」的，没人调用它就不存在。")
        print()

    # 耗时：★ 名字是 gen_ai.client.operation，不是 .duration ★
    # （这个坑我踩过：按文档里的常量名去查会 404，得看 actuator 报出来的实际名字。
    #   所以上面那行「所有 AI 相关指标」的清单才是最有用的 —— 先问它有什么，再查。）
    if "gen_ai.client.operation" in names:
        data = get("/actuator/metrics/gen_ai.client.operation")
        print("=== gen_ai.client.operation（累计耗时，秒）===")
        for m in data.get("measurements", []):
            print(f"  {m['statistic']:<10} = {m['value']:.4f}")
        print()


if __name__ == "__main__":
    main()

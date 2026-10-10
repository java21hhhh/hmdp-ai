# -*- coding: utf-8 -*-
"""
链路体检 —— 把 Zipkin 里的那棵树拉下来，画成文字版
=====================================================

用法:
    python3.12 traces.py           # 看最近 1 次
    python3.12 traces.py 5         # 看最近 5 次

和 metrics.py 的区别（这点很重要）:
    metrics.py 问的是 hmdp-ai（8082）的 /actuator/metrics   → 要带 X-Api-Key
    这个脚本   问的是 Zipkin（9411）的 /api/v2/traces       → 不需要钥匙

为什么？因为追踪数据【不在我们的服务里】——span 一产生就被上报走了，
我们的服务不留。Zipkin 是独立的第三方后端，我们只是它的客户。
（这正是"标准和实现分离"的好处：Zipkin 换成 Jaeger，这个脚本改个地址就行。）

它回答的问题是日志和指标都答不了的那个:
    【这一次】请求，时间到底被谁吃了？
"""
import json
import sys
import urllib.request

ZIPKIN = "http://localhost:9411"


def get(path):
    with urllib.request.urlopen(ZIPKIN + path, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def fmt_us(us):
    """Zipkin 里的时间单位是【微秒】,不是毫秒 —— 算错一位就全乱套。"""
    if us is None:
        return "-"
    ms = us / 1000.0
    if ms < 1000:
        return f"{ms:.0f}ms"
    return f"{ms / 1000:.2f}s"


def draw(trace):
    """一棵 trace 就是一堆扁平的 span,靠 parentId 自己拼成树。"""
    root = min(trace, key=lambda s: s.get("timestamp", 0))
    base = root.get("timestamp", 0)

    print(f"traceId:  {root['traceId']}")
    print(f"总耗时:   {fmt_us(root.get('duration'))}   共 {len(trace)} 个 span")
    print("-" * 72)
    print(f"{'偏移':>9}  {'耗时':>8}   链路")
    print("-" * 72)

    # 按 parentId 分组,每组按开始时间排序 —— 树不是 Zipkin 给的,是这么拼出来的
    children = {}
    for s in trace:
        children.setdefault(s.get("parentId"), []).append(s)
    for lst in children.values():
        lst.sort(key=lambda s: s.get("timestamp", 0))

    slowest = [None]

    def walk(span, prefix, is_last):
        kids = children.get(span["id"], [])
        branch = "└─ " if is_last else "├─ "
        svc = span.get("localEndpoint", {}).get("serviceName", "?")
        dur = span.get("duration")
        name = span["name"]
        if span.get("kind"):
            name = f"{name} ({span['kind'].lower()})"
        print(f"{fmt_us(span.get('timestamp', base) - base):>9}  {fmt_us(dur):>8}   "
              f"{prefix}{branch}{name}   [{svc}]")
        if dur and (slowest[0] is None or dur > slowest[0]["duration"]):
            slowest[0] = span
        next_prefix = prefix + ("   " if is_last else "│  ")
        for i, kid in enumerate(kids):
            walk(kid, next_prefix, i == len(kids) - 1)

    walk(root, "", True)

    print("-" * 72)
    if slowest[0] and slowest[0]["id"] != root["id"]:
        s = slowest[0]
        print(f"★ 最慢的一环: {s['name']}  ——  {fmt_us(s['duration'])}"
              f"（占总耗时 {s['duration'] * 100 // max(root.get('duration', 1), 1)}%）")
        print("  时间花在【谁】身上，看这一行就够了。")
    print()

    # span 上还能挂标签(tags)。框架埋的 GenAI 标签会带在这里,
    # 有的话顺便打出来 —— 这样"慢"和"贵"就能对上同一行。
    tagged = [s for s in trace if s.get("tags")]
    if tagged:
        print("附:带标签的 span")
        for s in tagged:
            pairs = ", ".join(f"{k}={v}" for k, v in s["tags"].items())
            print(f"  [{s['name']}] {pairs}")
        print()


def main():
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else 1

    try:
        services = get("/api/v2/services")
    except Exception as e:
        print(f"连不上 Zipkin ({ZIPKIN}) —— Zipkin 起了吗?")
        print(f"  Windows 上双击 C:\\Users\\HONOR\\zipkin\\启动Zipkin.bat")
        print(f"原始错误: {e}")
        return

    print(f"Zipkin 上登记的服务({len(services)} 个): {', '.join(sorted(services))}")
    print()

    if "hmdp-ai" not in services:
        print("hmdp-ai 还没上报过。检查两件事:")
        print("  1. 服务重启了吗? 改了 pom 和 yml,不重启不生效")
        print("  2. 重启后发过请求吗? 没请求就没 span")
        print("  发一次试试:  python3.12 stream_check.py 火锅")
        return

    traces = get(f"/api/v2/traces?serviceName=hmdp-ai&limit={limit}")
    if not traces:
        print("Zipkin 认识 hmdp-ai,但还没存到任何链路。发一次请求再来。")
        return

    print(f"拉取 hmdp-ai 最近 {len(traces)} 次链路")
    print("=" * 72)
    print()
    for trace in traces:
        draw(trace)


if __name__ == "__main__":
    main()

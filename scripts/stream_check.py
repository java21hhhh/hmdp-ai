# -*- coding: utf-8 -*-
"""
流式体检 —— 一格一格地看 Agent 到底什么时候吐出什么
=====================================================

用法:
    python3.12 stream_check.py 火锅
    python3.12 stream_check.py 103茶餐厅有什么券

它不看"答案对不对"，只看【时间线】：
    从发出请求到第一个字节，隔了多久？（TTFB）
    之后数据是一格一格来的，还是憋到最后一次性全来？
    中间那几轮工具调用，到底吐不吐内容？

为什么要先量这个
    因为"流式让页面变流畅"这句话，只有配上时间线才是真的。
    不然你只是在相信一个说法 —— 我们的规矩是：先跑出来，再下结论。

⚠️ 这个脚本用的是 /ai/agent/stream（新开的那道门），
   /ai/agent（给评测脚本用的那道门）一个字都没动。
"""

import os
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "eval_simple"))
import runner  # noqa: E402  —— 借它读好的 apikey.txt

BASE = "http://localhost:8082"


def main():
    question = sys.argv[1] if len(sys.argv) > 1 else "推荐一家有优惠券的店吃饭"

    params = {"question": question}
    url = BASE + "/ai/agent/stream?" + urllib.parse.urlencode(params)

    request = urllib.request.Request(url)
    request.add_header("X-Api-Key", runner.API_KEY)
    # 用全新桶，免得上一轮的记忆混进来
    request.add_header("X-Conversation-Id", "stream-" + time.strftime("%H%M%S"))

    print(f"问：{question}")
    print("=" * 64)

    started = time.time()
    first_byte_at = None
    total_bytes = 0
    pieces = []          # 把收到的碎片拼起来，最后还原成完整回答

    with urllib.request.urlopen(request, timeout=300) as response:
        # 有些服务器会告诉你它打算怎么发（chunked = 边生成边发）
        print(f"响应头 Transfer-Encoding: {response.headers.get('Transfer-Encoding')}")
        print(f"响应头 Content-Type:      {response.headers.get('Content-Type')}")
        print("-" * 64)

        while True:
            # read1(4096)：【最多】读 4096 字节，有多少读多少，
            # 不凑够 4096 不返回 —— 这正是观察"数据什么时候到"的关键。
            # 用 response.read() 会把整个响应读完才返回，那就什么都看不出来了。
            chunk = response.read1(4096)
            if not chunk:
                break

            now = time.time() - started
            if first_byte_at is None:
                first_byte_at = now
                print(f"[+{now:6.2f}s] ★ 第一个字节到达 ← 这就是用户开始看到东西的时刻")
            total_bytes += len(chunk)
            text = chunk.decode("utf-8", errors="replace")
            pieces.append(text)
            print(f"[+{now:6.2f}s] +{len(chunk):4d}B  {text!r}")

    elapsed = time.time() - started

    print("=" * 64)
    if first_byte_at is None:
        print("一个字节都没收到 —— 服务起了吗？路由对吗？")
        return
    print(f"第一个字节:  +{first_byte_at:.2f}s   ← 用户等了这么久才看到第一个字")
    print(f"全部结束:    +{elapsed:.2f}s")
    print(f"总共收到:    {total_bytes} 字节，分 {len(pieces)} 格到达")
    print()
    print("拼起来的完整回答：")
    print("".join(pieces))


if __name__ == "__main__":
    main()

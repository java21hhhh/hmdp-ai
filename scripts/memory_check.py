# -*- coding: utf-8 -*-
"""
记忆持久化验证 —— 两阶段脚本
=============================

用法：
    python3.12 memory_check.py plant     # 第 1 阶段：往记忆里种一个事实
    <然后重启 hmdp-ai 服务>
    python3.12 memory_check.py recall    # 第 2 阶段：问它那个事实

为什么必须是两个阶段、中间夹一次重启？
    因为我们要证的是"记忆活过了重启"。
    不重启的话，记忆还在内存里，答对了也说明不了任何事 ——
    这正是之前踩过的坑："重启换来的绿"和"真修好的绿"长得一模一样。

为什么用"幸运数字 7391"这种没头没脑的东西？
    7391 不是任何工具能查出来的，也不可能是模型编出来的巧合。
    它只能来自一件事：上一次请求存进 H2 数据库、这次又从数据库读出来了。

会话桶名固定成 restart-check-1（不随时间戳变）：
    就是要让两次请求命中【同一个桶】。换个桶 = 换一段对话 = 当然记不住。
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval_simple"))
import runner  # noqa: E402

CONVERSATION_ID = "restart-check-1"

PLANT_QUESTION = (
    "请记住两件事：我叫黄夏锐，我的幸运数字是 7391。"
    "只需回复「记住了」三个字，不要调用任何工具。"
)

RECALL_QUESTION = (
    "我叫什么名字？我的幸运数字是多少？"
    "直接回答，不要调用任何工具。"
)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "plant"
    question = PLANT_QUESTION if mode == "plant" else RECALL_QUESTION

    print("=" * 60)
    print(f"阶段: {mode}")
    print(f"会话桶: {CONVERSATION_ID}")
    print(f"问题: {question}")
    print("=" * 60)

    result = runner.ask(question, None, CONVERSATION_ID, None)

    print(f"工具调用: {result.get('toolCalls')}")
    print(f"耗时: {result.get('elapsedMs')} ms")
    print("-" * 60)
    print("模型回答:")
    print(result.get("answer"))


if __name__ == "__main__":
    main()

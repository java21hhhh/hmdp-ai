# -*- coding: utf-8 -*-
"""
【第 4 个文件】main.py —— 主程序（从这儿开始看）
==================================================

它自己只干三件事：读 case → 发请求 → 打印结果。
真正的活儿都分给了上面三个文件：

    cases.py    给数据      （测什么）
    runner.py   发请求      （去问）
    judge.py    判对错      （打分）
    main.py     串起来      （你在这儿）

用法（和以前一样，必须在 eval_simple 这个文件夹的上一层运行也行）：
    PYTHONIOENCODING=utf-8 python3.12 main.py           跑全部
    PYTHONIOENCODING=utf-8 python3.12 main.py 02 05     只跑这两条
"""

import sys
import time

import cases          # 第 1 个文件：数据
import runner         # 第 2 个文件：发请求
import judge          # 第 3 个文件：判对错

# 这次运行的时间戳，比如 "153012"。
# 括号里是格式：%H 时、%M 分、%S 秒。
RUN_STAMP = time.strftime("%H%M%S")

# cases.py 的 token 字段里，写这个值 = 去 token.txt 读真登录凭证（下单要登录）
REAL_FLAG = "@real"


def make_headers(case):
    """
    算出这一条 case 这次该用哪两个凭证。

    ★ 这两个东西是不同的，以前被焊成了一个：
        authorization            = 你是谁（登录凭证）
        桶名（X-Conversation-Id）= 这是哪段对话（记忆的桶名）

    为什么桶名要加时间戳？
      用同一个桶名，上一轮模型自己说过的话就还在桶里，
      这一轮它【不再查工具】，直接把上次的答案背一遍 —— 分数全是假的。
      加个时间戳，每次运行都是全新的桶。

    ⚠️ 以前桶名就是 token 本身，所以真 token 加不了时间戳（加了就 401），
      05 那条只能一直串着记忆。现在 Java 那边把两件事拆开了，
      桶名可以【每条 case、每次运行】都换新的。
    """
    # 桶名：永远带时间戳 → 每次都是全新的桶
    conversation_id = case["id"] + "-" + RUN_STAMP

    if case["token"] == REAL_FLAG:
        return runner.REAL_TOKEN, conversation_id    # 下单要登录，带真凭证
    return None, conversation_id                     # 查询类不需要登录


def main():
    # 命令行上写了哪些 case 编号？不写就是空列表 = 全跑
    wanted = sys.argv[1:]

    print("hmdp-ai Agent 评测")
    print(f"运行时间戳：{RUN_STAMP}")
    print()

    passed = 0
    total = 0
    total_cost = 0.0        # 这一轮评测一共花了多少钱（估算）

    for case in cases.CASES:
        # 指定了编号，而这一条不在里面 → 跳过
        if wanted and case["id"] not in wanted:
            continue

        total = total + 1

        print("-" * 70)
        print(f"case {case['id']}：{case['note']}")
        print(f"问：{case['question']}")

        auth, conversation_id = make_headers(case)
        auth_note = "真凭证（下单要登录）" if auth else "无（查询类不用登录）"
        print(f"   authorization: {auth_note}｜X-Conversation-Id: {conversation_id}")

        # ── 第 2 个文件：发请求 ──────────────────────────
        try:
            result = runner.ask(case["question"], auth, conversation_id, case["confirm"])
        except Exception as error:
            print(f"   ❌ 请求发不出去：{error}")
            continue          # 这一条不算通过，接着跑下一条

        # 服务器没返回这两个字段时，给个安全的默认值
        tool_calls = result.get("toolCalls")
        if tool_calls is None:
            tool_calls = []
        answer = result.get("answer")
        if answer is None:
            answer = ""

        # ── 第 3 个文件：判对错 ──────────────────────────
        executed, blocked = judge.split_calls(tool_calls)
        fails = judge.check(case, executed, blocked)

        # ── 打印 ─────────────────────────────────────────
        print(f"工具记录：{tool_calls}")
        print(f"执行 {len(executed)} 次｜被拦 {len(blocked)} 次｜"
              f"耗时 {result.get('elapsedMs')} ms")

        # ── 账本 ─────────────────────────────────────────
        # 服务端新加的两个字段：usage 是总账，rounds 是逐轮明细。
        # 这是"能观测"的第一步 —— 评测不只回答「对不对」，还回答「花了多少」。
        #
        # ★ 老服务（没改 Java 之前）没有这两个字段，result.get 会返回 None，
        #   下面的代码会安静地跳过，不会报错。这就是 .get() 比 ["键"] 好的地方。
        usage = result.get("usage")
        if usage:
            cost = usage.get("estimatedCostCny")
            if cost is None:
                cost = 0.0
            total_cost = total_cost + cost
            print(f"   [账本] 总账：prompt={usage.get('promptTokens')}  "
                  f"completion={usage.get('completionTokens')}  "
                  f"合计={usage.get('totalTokens')} tokens，约 ¥{cost:.4f}")

        for r in (result.get("rounds") or []):
            print(f"   [账本]   第{r.get('index')}轮  "
                  f"prompt={r.get('promptTokens'):<6} "
                  f"completion={r.get('completionTokens'):<6} "
                  f"合计={r.get('totalTokens')}")

        one_line = answer.replace("\n", " ")     # 回答是多行的，压成一行好读
        print(f"回答：{one_line[:120]}")

        if fails:
            print("❌ 不通过")
            for reason in fails:
                print(f"   - {reason}")
        else:
            print("✅ 通过")
            passed = passed + 1

    print("=" * 70)
    print(f"得分：{passed}/{total}")
    # ★ 跑一次评测的成本。以前这个数字是"感觉挺贵的"，现在它是个数。
    print(f"本次评测总花费：约 ¥{total_cost:.4f}")


# 这一行是 Python 的固定写法，意思是：
# "只有直接运行 main.py 时才执行 main()，被别人 import 时不执行"。
if __name__ == "__main__":
    main()

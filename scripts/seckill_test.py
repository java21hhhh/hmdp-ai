# -*- coding: utf-8 -*-
"""
秒杀护栏 —— 联调测试脚本。

用法（token 可选，不传就是"匿名用户"）：
    python3.12 seckill_test.py 5b07de4860174df18bc34e354de133e3

★ 需要两个凭证文件，它们【不在这个仓库里】，要放在仓库外面：
    apikey.txt  hmdp-ai 的 X-Api-Key
    token.txt   用户 1011 的登录 token
  默认去 ~/hmdp-ai-secrets/ 找，也可以用环境变量 HMDP_SECRETS_DIR 指路。
"""
import json
import os
import sys
import urllib.parse
import urllib.request

PORT = "8082"
BASE = "http://localhost:" + PORT

# ════════════════════════════════════════════════════════════════════
#  凭证从仓库【外面】读 —— 这个仓库里一个密钥文件都没有
#  找的顺序：① $HMDP_SECRETS_DIR ② ~/hmdp-ai-secrets/ ③ ~/hmdp-ai-scripts/
# ════════════════════════════════════════════════════════════════════
_SECRET_DIRS = [
    os.environ.get("HMDP_SECRETS_DIR"),
    os.path.join(os.path.expanduser("~"), "hmdp-ai-secrets"),
    os.path.join(os.path.expanduser("~"), "hmdp-ai-scripts"),
]


def _read(name, required=True):
    """去仓库外面找凭证文件。找不到就报错，别悄悄返回 None 变成一次 401。"""
    for d in _SECRET_DIRS:
        if not d:
            continue
        path = os.path.join(d, name)
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as f:
                return f.read().strip() or None

    if required:
        raise SystemExit(
            "\n找不到 " + name + "。\n"
            "  它是 hmdp-ai 的访问凭证，故意【不放进仓库】。\n"
            "  请把它放到：" + _SECRET_DIRS[1] + "\n"
            "  或者用环境变量 HMDP_SECRETS_DIR 指一个目录给我。\n"
        )
    return None


TOKEN = sys.argv[1] if len(sys.argv) > 1 else _read("token.txt", required=False)

# 门卫要的钥匙：hmdp-ai 的 X-Api-Key
API_KEY = _read("apikey.txt")

print("token:  ", (TOKEN[:8] + "..." + TOKEN[-4:]) if TOKEN else "(无，匿名)")
print("apiKey: ", API_KEY or "(无 —— 会被门卫 401 挡掉)")
print()

# 每步：(标题, 问题, confirm 参数)
#   confirm=None  → 不带确认信号（模拟"用户还没点确认"）
#   confirm=10    → 前端在用户点了确认之后加上的
#   ★ 它发的时候走【请求头】X-Confirm-Voucher-Id，不走 URL
#     （2026-10-10 从 ?confirm=10 挪过来的，理由见 AiController 类注释）
STEPS = [
    ("第 1 轮：问券（不带确认信号）",
     "103茶餐厅有什么券？", None),

    ("第 2 轮：确认（X-Confirm-Voucher-Id: 10）—— 看它这次报什么 id",
     "确认", 10),

    ("第 3 轮：再确认一次（X-Confirm-Voucher-Id: 10）—— 拦下来的信息它用不用得上",
     "确认", 10),
]


def call(question, token, confirm, timeout=300):
    params = {"question": question}
    url = BASE + "/ai/agent?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url)
    # 门卫那把钥匙：不过这一关，请求根本进不去 Controller
    if API_KEY:
        req.add_header("X-Api-Key", API_KEY)
    # 用户凭证：只有下单（写操作）才用得上
    if token:
        req.add_header("authorization", token)
    # ★ 确认信号：走请求头，不走 URL
    if confirm is not None:
        req.add_header("X-Confirm-Voucher-Id", str(confirm))
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


for label, q, confirm in STEPS:
    print("=" * 74)
    print(label)
    print("问题：", q, "（confirm =", confirm, "）")
    print("=" * 74)
    try:
        d = call(q, TOKEN, confirm)
    except Exception as e:
        print("  请求失败：", type(e).__name__, e)
        try:
            print("  返回体：", e.read().decode("utf-8", "replace")[:800])
        except Exception:
            pass
        print()
        continue

    calls = d.get("toolCalls") or []
    print("模型调用工具 %d 次，耗时 %s ms" % (len(calls), d.get("elapsedMs")))
    for i, c in enumerate(calls, 1):
        print("   %d. %s" % (i, c))
    print()
    print("回答：")
    print(d.get("answer"))
    print()

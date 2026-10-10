# -*- coding: utf-8 -*-
"""
【第 2 个文件】runner.py —— 发请求
====================================

这个文件只干一件事：把一个问题发给 hmdp-ai，把返回的 JSON 拿回来。
它不知道什么叫"对错"—— 判对错是 judge.py 的事。
"""

import json
import os
import urllib.parse
import urllib.request

# hmdp-ai 的地址
PORT = "8082"
BASE = "http://localhost:" + PORT

# ════════════════════════════════════════════════════════════════════
#  凭证从哪儿读 —— ★ 这个仓库里【一个密钥文件都没有】★
# ════════════════════════════════════════════════════════════════════
#
#  为什么不在脚本旁边放 apikey.txt：
#    这个仓库是要推上 GitHub 的。密钥文件一旦进了工作区，
#    就只剩"那行 .gitignore 写对了没有"这一道防线 —— 一道防线不够。
#    所以改成【从仓库外面读】：仓库里零密钥，怎么手滑都不会泄。
#
#  找的顺序（前一个没有再找下一个）：
#    ① 环境变量 HMDP_SECRETS_DIR 指的目录
#    ② ~/hmdp-ai-secrets/          ← 推荐放这儿
#    ③ ~/hmdp-ai-scripts/          ← 历史位置，留作兼容
#
#  每个文件就一行内容（别加引号、别加多余换行）：
#    apikey.txt  →  hmdp-ai 的 X-Api-Key（和 .env / 启动.bat 里那个一样）
#    token.txt   →  用户 1011 的登录 token（只有下单那条用例用得到）
_SECRET_DIRS = [
    os.environ.get("HMDP_SECRETS_DIR"),
    os.path.join(os.path.expanduser("~"), "hmdp-ai-secrets"),
    os.path.join(os.path.expanduser("~"), "hmdp-ai-scripts"),
]


def read_secret(filename, required=True):
    """去仓库外面找一个凭证文件，去掉首尾空白。

    找不到时：required=True 就直接报错停下 —— 别悄悄返回 None，
    那会变成一次莫名其妙的 401，够你查半小时。
    """
    for d in _SECRET_DIRS:
        if not d:
            continue
        path = os.path.join(d, filename)
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as f:
                return f.read().strip() or None

    if required:
        raise SystemExit(
            "\n找不到 " + filename + "。\n"
            "  它是 hmdp-ai 的访问凭证，故意【不放进仓库】。\n"
            "  请把它放到：" + _SECRET_DIRS[1] + "\n"
            "  或者用环境变量 HMDP_SECRETS_DIR 指一个目录给我。\n"
        )
    return None


# 两个凭证只在程序启动时读一次
API_KEY = read_secret("apikey.txt")                        # hmdp-ai 的门卫钥匙
REAL_TOKEN = read_secret("token.txt", required=False)      # 用户 1011 的登录凭证


def ask(question, token, conversation_id, confirm, timeout=300):
    """
    发一次请求，返回一个字典。

    拿回来的字典长这样（就是 Java 那边 AgentResult 的内容）：
        {
            "question":  "你问的话",
            "answer":    "模型最终的回答",
            "toolCalls": ["listVouchers(1)", ...],   ← ★ 我们要判的就是这个
            "elapsedMs": 2400
        }
    """
    # 把参数拼到 URL 后面。
    # 中文不能直接放进 URL，urlencode 会替我们做 percent-encode。
    params = {"question": question}
    url = BASE + "/ai/agent?" + urllib.parse.urlencode(params)

    request = urllib.request.Request(url)

    # 这几个头作用完全不同，别搞混：
    if API_KEY:
        # ① 门卫的钥匙。不带它，请求根本进不了 Controller，直接 401。
        request.add_header("X-Api-Key", API_KEY)
    if token:
        # ② 你是谁（登录凭证）。只有下单那种写操作才需要。
        request.add_header("authorization", token)
    if confirm is not None:
        # ④ 用户"已确认"的信号（里面装的是券 id）。
        #    ★ 它走请求头，不走 URL：URL 会进访问日志/浏览器历史/Referer。
        #    ⚠️ 但它的原则没变 —— 仍然是【HTTP 参数】，
        #       模型在工具参数里传什么都不算数。见 AiController 类注释。
        request.add_header("X-Confirm-Voucher-Id", str(confirm))
    if conversation_id:
        # ③ 这是哪段对话（记忆的桶名）。
        #    ★ 以前 ② 和 ③ 是同一个 token，所以真 token 加不了时间戳（加了就 401）。
        #      现在 Java 那边把两件事拆开了，桶名可以每次都换新的。
        request.add_header("X-Conversation-Id", conversation_id)

    with urllib.request.urlopen(request, timeout=timeout) as response:
        text = response.read().decode("utf-8")
        return json.loads(text)

# scripts —— hmdp-ai 的体检工具集

这些不是业务代码，是**用来证明业务代码真的对**的脚本。

一个 AI 服务最危险的地方在于：它答得头头是道，你却不知道它到底是真查了、
还是编的；是防守住了、还是今天碰巧很乖。这一目录里的每个脚本，都在把
"我觉得它应该没问题"换成"我跑过，这是数字"。

---

## ★ 第一步：密钥放在仓库【外面】★

这不是可选的。**这个仓库里一个密钥文件都没有，而且故意不放。**

脚本要两个凭证：

| 文件名 | 内容 | 谁用得到 |
|---|---|---|
| `apikey.txt` | hmdp-ai 的 `X-Api-Key`（和 `.env`、`启动.bat` 里那个一样） | 全部脚本 |
| `token.txt` | 用户 1011 的登录 token | 只有"下单"那几条用例 |

每个文件**就一行内容**，别加引号、别加多余换行。放到下面任一位置即可：

```bash
# 推荐：新建一个专门放密钥的目录
mkdir -p ~/hmdp-ai-secrets
printf '%s' '你的X-Api-Key'  > ~/hmdp-ai-secrets/apikey.txt
printf '%s' '用户1011的token' > ~/hmdp-ai-secrets/token.txt
```

脚本按这个顺序找（前一个没有再找下一个）：

1. 环境变量 `HMDP_SECRETS_DIR` 指的目录
2. `~/hmdp-ai-secrets/`
3. `~/hmdp-ai-scripts/` ← 历史位置，留作向后兼容

> **为什么不放在脚本旁边？**
> 因为密钥文件一旦进了仓库工作区，就只剩"那行 `.gitignore` 写对了没有"
> 这一道防线 —— 一道防线不够。改成从外面读，**仓库里零密钥，怎么手滑都不会泄。**
> 找不到文件时脚本会**直接报错停下**，不会悄悄返回空值变成一次莫名其妙的 401。

---

## 前提：先把服务跑起来

这些脚本都是 HTTP 客户端，得先有服务在听：

```
MySQL :3306  →  Redis :6379  →  hmdp :8081  →  hmdp-ai :8082
                                                 ↑ 脚本连这儿
```

最快的自检（连上面四层都不用，只要 8082 活着）：

```bash
curl -H "X-Api-Key: 你的key" "http://localhost:8082/ai/agent?question=hi"
```

---

## 脚本清单

| 脚本 | 它回答的问题 |
|---|---|
| `seckill_test.py` | 手动走一遍「确认门 / 硬护栏」，**看过程**（人盯着屏幕读） |
| `eval_agent.py` | 5 条 case 自动判分，**看结果**（机器判对错，最后给个分数） |
| `eval_simple/` | 上面那条评测的「拆开讲」版：四个文件，每个只干一件事 |
| `memory_check.py` | 记忆**活过重启**了吗（种一个事实 → 重启 → 问它还记得不） |
| `metrics.py` | 账本：这几轮对话一共烧了多少 token（读 `/actuator/metrics`） |
| `stream_check.py` | 流式：第一个字节多久到？数据是一格一格来的，还是憋到最后？ |
| `traces.py` | 链路：**这一次**请求的时间，到底被谁吃了（读 Zipkin 的 9411） |

Windows 上跑带中文的脚本，前面加 `PYTHONIOENCODING=utf-8`，否则中文参数会在
URL 编码前就被控制台按 GBK 啃坏。

---

## 逐个怎么跑

### `seckill_test.py` —— 肉眼走一遍下单

```bash
PYTHONIOENCODING=utf-8 python3.12 seckill_test.py
# 也可以自己传一个 token：python3.12 seckill_test.py <token>
```

三轮：先问券（不带确认信号）→ 再确认（`confirm=10`）→ 又确认一次。
看的是它第 2、3 轮分别报什么 id、拦下来的信息它用不用得上。

### `eval_agent.py` —— 打分

```bash
PYTHONIOENCODING=utf-8 python3.12 eval_agent.py           # 跑全部
PYTHONIOENCODING=utf-8 python3.12 eval_agent.py 02 05     # 只跑指定 case
PYTHONIOENCODING=utf-8 python3.12 eval_agent.py 02 -n 3   # 同一条跑 3 次
PYTHONIOENCODING=utf-8 python3.12 eval_agent.py 03 --reuse  # 故意复用记忆（看污染）
```

**判的是「真的执行了」，不是「调了」** —— 被护栏拦下的尝试不算数。
这一条是整个评测的关键，文件头的注释解释得很细，动手改 case 之前先看。

它还会报一种最阴的绿：**空过**（用例绿了，但模型压根没去撞那道门，
所以那道门这次根本没被测到）。"今天很乖"不等于"门装好了"。

### `eval_simple/` —— 想看懂评测是怎么写的，从这儿进

四个文件，各管一段，可以单看：

```
cases.py    测什么      （只有数据，没有逻辑）
runner.py   去问        （发请求，把 JSON 拿回来）
judge.py    判对错      （只吃工具调用记录，不看模型的回答文字）
main.py     串起来      （你从这儿开始看）
```

```bash
cd eval_simple
PYTHONIOENCODING=utf-8 python3.12 main.py
PYTHONIOENCODING=utf-8 python3.12 main.py 02 05
```

### `memory_check.py` —— 记忆有没有活过重启

必须**分两阶段、中间夹一次重启**，否则证明不了任何事（记忆还在内存里，
答对了也说明不了问题）：

```bash
PYTHONIOENCODING=utf-8 python3.12 memory_check.py plant   # 种下"幸运数字 7391"
# ← 现在去重启 hmdp-ai 服务
PYTHONIOENCODING=utf-8 python3.12 memory_check.py recall  # 问它还记不记得
```

7391 不是任何工具查得到的，也不可能是巧合 —— 它只可能来自 H2 数据库。

### `metrics.py` / `stream_check.py` / `traces.py`

```bash
PYTHONIOENCODING=utf-8 python3.12 metrics.py            # 累计 token 账本
PYTHONIOENCODING=utf-8 python3.12 stream_check.py 火锅   # 流式时间线
PYTHONIOENCODING=utf-8 python3.12 traces.py 5           # 最近 5 条链路
```

`traces.py` 连的是 Zipkin（9411），不需要 `X-Api-Key` —— 因为追踪数据
不在我们的服务里，span 一产生就上报走了。

> 服务用 `deploy/docker-compose.yml` 起的时候，zipkin 只绑了 `127.0.0.1`，
> 而且挂在 `trace` profile 下。要看得先在 `.env` 里打开它，再 SSH 隧道进来。
> 本地直接跑四个服务则不用管这些。

---

## 一句话原则

> **先跑出来，再下结论。**

这几个脚本加起来不复杂，但它们把"我觉得没问题"变成了"这是我看到的
数字、这是它撞门时的原文"。改完 prompt、换完模型之后，重跑一遍 ——
分数掉了，先别急着改 prompt，先排除一种可能：**评测环境自己脏了**
（记忆污染、服务没重启、token 重复）。

# hmdp-ai

> 给「黑马点评」（hmdp）接的一个 AI 助手服务。
>
> **它不是一个聊天机器人 —— 是一个被两道门锁住的 AI 服务员。**

Java 21 · Spring Boot 4.1 · Spring AI 2.0 · DeepSeek · MySQL · Redis · Zipkin · Docker

---

## 这是什么

黑马点评是一套经典的 Redis 实战项目：店铺、优惠券、秒杀下单。
它的后端我早就写完了，这个仓库是**在它旁边新起的一个 AI 服务**。

两者是**两个独立进程，通过 HTTP 调用**：

```
hmdp      :8081   Spring Boot 2.7.4（javax.*）—— 业务系统，我不动它
hmdp-ai   :8082   Spring Boot 4.1  （jakarta.*）—— 这个仓库
```

> **为什么不合并成一个服务？**
> 因为 `javax.*` 和 `jakarta.*` 是两套不兼容的包名。Boot 3 之后 `javax.servlet` 全部改成
> `jakarta.servlet`，一个 JVM 里放不下两代。硬合并要动老项目的几百个 import ——
> 而**把它们当成两个服务、用 HTTP 通信**，不仅零改动，还更接近真实架构。
> 这也顺带解释了为什么 AI 能力通常是「旁挂」到已有系统上，而不是推倒重来。

---

## 30 秒：两个版本，差别一目了然

同一个需求（"帮我找家店吃饭"），我写了两版：

| | **Workflow 版** | **Agent 版** |
|---|---|---|
| 接口 | `/ai/recommend?keyword=火锅` | `/ai/agent?question=推荐一家有优惠券的店吃饭` |
| 入参 | 一个**关键词** | 一句话**意图** |
| 谁定流程 | **我**写死：搜索 → 拼上下文 → 模型润色 | **模型自己**决定调哪个工具、调几次 |
| 模型干什么 | 把数据写成一段人话 | 自己规划、自己重试 |
| 返回里有什么 | `answer` + `factsSentToModel` | `answer` + `toolCalls` + `usage` + `rounds` |

参数名从 `keyword` 变成 `question`，**不是随手改的** ——
从"我要什么参数"变成"用户想干嘛"，这正是从传统开发滑向 AI 开发的信号。

试一下「火锅」那条，你会看到模型搜完之后发现没券，**自己换了个词接着搜**。
那个"接着搜"的动作，就是分界线。

---

## 架构

```
              ┌─────────────────────── 浏览器 / curl / 评测脚本 ───────────────────────┐
              │                       X-Api-Key（门卫）                                │
              └───────────────────────────────┬───────────────────────────────────────┘
                                              ▼
                                    ┌───────────────────┐
                                    │    hmdp-ai :8082  │   ← 唯一对公网开口的服务
                                    │   ApiKeyFilter    │   每个请求都要钥匙，否则 401
                                    │   AiController    │
                                    │   AiAgentService  │───┐ 记忆（H2 文件库，重启不丢）
                                    │   HmdpTools ×5    │   └─ 账本 / 指标 / 链路
                                    └─────────┬─────────┘
                                              │ HTTP
                                              ▼
                                    ┌───────────────────┐
                                    │     hmdp :8081    │   ← 业务系统（无鉴权，只在容器内网活着）
                                    └─────────┬─────────┘
                                              │
                                     ┌────────┴────────┐
                                     ▼                 ▼
                                 MySQL :3306      Redis :6379

              hmdp-ai ──── 文本生成 ────▶ DeepSeek（api.deepseek.com）
```

**交给模型的 5 个工具**（`@Tool`，见 `tool/HmdpTools.java`）：

| 工具 | 干什么 |
|---|---|
| `searchShopsByName(keyword, current)` | 按店名搜店铺 |
| `listVouchers(shopId)` | 查某家店有什么券 |
| `listShopTypes()` | 列出所有店铺分类 |
| `listShopsByType(typeId, current)` | 按分类列出店铺 |
| `seckillVoucher(voucherId)` | **秒杀下单**（唯一的写操作） |

---

## 三个真正值得看的地方

### 一、两道门：AI 能"想"，但不能"随便动手"

一个能下单的 AI，最危险的不是它答错，是**它真的把订单提交了**。
所以我没有靠 prompt 里写"请不要擅自下单"（那是**请求**，不是**约束**），而是在代码里装了两道门：

**第一道 · 确认门**（`HmdpTools.java`）
`seckillVoucher` 要拿到一个 `confirmVoucherId`，而这个值**只能来自请求头
`X-Confirm-Voucher-Id`**（最初走的是 URL 上的 `?confirm=`，后来挪进了请求头 ——
URL 会进访问日志 / 浏览器历史 / `Referer`，而"某个动作已被授权"这种信息不该留在 URL 上；
理由见 `AiController` 类注释）。**模型在工具参数里传什么一律不算数**：

```java
if (confirmVoucherId == null || !confirmVoucherId.equals(voucherId)) {
    return "缺少用户确认，不能下单";
}
```

用户嘴上说"帮我买"，模型真去调了，也照样被拦——因为**门认的是请求头里那个值，
不认模型的判断**（换了位置，没换原则）。这一条是整个项目的核心：

> **凡是你不能交给模型的东西，就别让它在模型手里。**
> token 是这样，确认是这样，API Key 也是这样（所以它们全在 HTTP 头里，不在工具参数里）。

**第二道 · 硬护栏**
就算确认门开了，同一段对话里同一张券也只能抢一次（`triedVoucherIds` 集合）。

这两道门都有**评测盯着**（见下面「评测」）——不是靠"我觉得拦住了"。

### 二、记忆，而且**活过重启**

对话记忆存在 **H2 文件库**（`jdbc:h2:file:./data/...`），不是内存。重启服务之后，
上次聊的内容还在。而且**对话是按 `X-Conversation-Id` 分桶的** ——
"谁在说话"（`authorization`）和"这是哪段对话"（`X-Conversation-Id`）是两个东西，
拆开之后，同一个用户的不同会话不会串味。

> **开发用 H2，生产可切。** Spring AI 的 `ChatMemoryRepository` 是**可替换实现** ——
> 要换 MySQL / PostgreSQL，换的是这一个 Bean，业务代码一行不动。
> H2 文件库解决的是「**开发期重启不丢**」这个问题，不是生产选型；
> 生产该用哪个库，取决于你要不要多实例共享记忆、要不要独立备份。

> 这块有个反直觉的坑，我实测过并写进了注释：**工具返回的内容不进记忆，
> 但模型自己说过的话进记忆。** 于是上一轮的"答案"会变成这一轮的"事实" ——
> 模型不再查工具，直接把上次的话背一遍。所以评测脚本每次运行都必须换一批 token。
> 详见 `scripts/eval_agent.py` 第 4 部分的「记忆隔离」。

### 三、可观测性三根柱子 + 一套评测

「AI 服务」最容易变成黑盒：它答得头头是道，你**不知道它烧了多少钱、慢在哪**。
所以补了三根柱子，各回答一个问题：

| 柱子 | 看什么 | 在哪 |
|---|---|---|
| 日志 `[账本]` | 刚才发生了什么，**逐轮**烧了多少 token | `RoundUsageRecorder`（挂了框架的 observation 钩子，零侵入） |
| 指标 `/actuator/metrics` | 一共烧了多少、整体趋势 | `gen_ai.client.token.usage` |
| 链路 Zipkin | **这一次**的十几秒，时间花在谁身上 | `management.tracing.*`，Java 代码一行没动 |

日志里还塞了 `traceId`，于是 **Zipkin 上点那根最慢的条，就能顺着 id 找到它当时的日志**——
横向看树、纵向看细节，这才叫"可观测"。

---

## 跑起来

### 前置：两个环境变量（**少了会以两种不同方式炸**）

```bash
DEEPSEEK_API_KEY   # DeepSeek 的 key，形如 sk-...
HMDP_AI_API_KEY    # 本服务的门卫钥匙，随便一串够长的随机字符
```

> ⚠️ **两个都缺不得，而且症状完全不同**：
> 少 `HMDP_AI_API_KEY` → **服务根本起不来**（故意的：宁可起不来，也不要"以为保护了、其实裸奔"）。
> 少 `DEEPSEEK_API_KEY` → **能起来，但一问就 500**，日志里只有一句含糊的报错。
> 这一条我踩过，记在这儿。

### 本地（开发用）

```bash
# ① 先把业务系统 hmdp 跑起来（MySQL:3306 和 Redis:6379 也得在）
#    它跑在 8081，别和 nginx 的 8080 搞混

# ② 本服务
mvn clean package -DskipTests
java -jar target/hmdp-ai-0.0.1-SNAPSHOT.jar

# ③ 试一下（actuator 也被门卫拦着，要带钥匙）
curl -H "X-Api-Key: $HMDP_AI_API_KEY" \
     "http://localhost:8082/ai/agent?question=推荐一家有优惠券的店吃饭"
```

### Docker（部署用，5 个容器一键起）

```bash
cd deploy
cp .env.example .env   # 填三个值
docker compose up -d --build
```

完整的服务器部署手册（安全组、镜像加速、SSH 隧道看 Zipkin、故障排查表）在
**[`deploy/README.md`](deploy/README.md)**。

---

## 接口一览

| 方法 | 路径 | 给谁用 | 说明 |
|---|---|---|---|
| GET | `/ai/recommend?keyword=` | 人 | Workflow 版，返回 JSON |
| GET | `/ai/agent?question=` | **机器**（评测脚本） | Agent 版，返回完整 JSON |
| GET | `/ai/agent/stream?question=` | 人 | Agent 版，**SSE 流式** |

三个自定义请求头，作用完全不同，别搞混：

| 头 | 是什么 | 不带的后果 |
|---|---|---|
| `X-Api-Key` | 门卫钥匙 | **401**，请求进不了 Controller |
| `authorization` | 用户登录凭证 | 查询类无所谓；**下单会失败** |
| `X-Conversation-Id` | 这是哪段对话（记忆的桶名） | 退回用 `authorization` 当桶名 |

> 为什么 `/agent` 和 `/agent/stream` 是两个接口，而不是把 `/agent` 改造成流式？
> 因为 `/agent` 返回的是**一个完整 JSON**，评测脚本用 `json.loads` 解析它 ——
> 流式是一格一格吐的，`json.loads` 会当场炸。
> **给机器用的接口，别为了让新功能好看就改掉，已经有人在消费它了。**

---

## 评测：把「跑一遍看看」变成「跑一遍打个分」

`scripts/` 下是一套完整的体检工具 —— **这是我认为这个项目最值钱的部分**。
一个 AI 应用的"安全"和"正确"，不能靠感觉。

```bash
cd scripts
PYTHONIOENCODING=utf-8 python3.12 eval_agent.py       # 5 条用例自动判分
```

评测的核心是判**「真的执行了」而不是「调了」**：被门拦下的尝试不算数。
否则"模型确实试着下单了"这条会永远变红——那测的就不是安全性，是模型胆量。
它还会揪出一种最阴的绿：**空过**（用例绿了，但模型压根没去撞那道门，
所以那道门这次根本没被测到）。

> ★ **凭证明文不放进这个仓库。** 脚本从仓库外面读（`$HMDP_SECRETS_DIR`
> → `~/hmdp-ai-secrets/`）。**仓库里一个密钥文件都没有。**
> 每个脚本干嘛、密钥怎么放，见 **[`scripts/README.md`](scripts/README.md)**。

### 评测是**人手动跑的**，CI 不跑它

这不是没搭 CI —— CI 在 [`.github/workflows/ci.yml`](.github/workflows/ci.yml)，
但**它刻意不跑评测**。两个理由：评测要真调 DeepSeek（每次都是钱），
而且要在 CI 里放 `DEEPSEEK_API_KEY` 和 `X-Api-Key`（那等于把密钥交给一个你管不了的环境）。

所以分工是：**CI 管「编得过、语法没写错、没把密钥提交上去」，
人管「行为对不对」。** 前者机器一秒能判，后者机器判不了 —— 硬塞给 CI 只会得到一个假绿。

---

## 目录结构

```
src/main/java/com/hxr/hmdpai/
├── config/
│   ├── ApiKeyFilter.java       门卫：每个请求都要 X-Api-Key
│   └── HmdpClientConfig.java   RestClient 配置
├── controller/AiController.java 三个接口
├── service/
│   ├── AiRecommendService.java Workflow 版：我定流程
│   └── AiAgentService.java     Agent 版：交给模型，含两道门的上下文
├── tool/HmdpTools.java         ★ 5 个 @Tool + 两道门
├── client/HmdpClient.java      调 hmdp 的 HTTP 客户端
├── usage/RoundUsageRecorder.java  逐轮记账（observation 钩子）
└── dto/                        工具返回值的"视图"对象

scripts/     体检工具集（评测 / 记忆 / 指标 / 流式 / 链路）
deploy/      Docker 部署包（compose + 冒烟脚本 + 部署手册）
.github/     CI：只做编译级检查（编得过 / 语法对 / 没混进密钥）
```

---

## 踩过的坑（挑几个真的会再踩的）

- **`MaxRAMPercentage` 不设容器内存上限就没用。** JVM 会退回去读**整机**内存，
  4G 的机器上两个 JVM 都以为自己能长到 2.8G，然后一起 OOM——症状是容器**莫名其妙消失**
  （exit 137），而不是报错。所以 `docker-compose.yml` 里每个服务都写了 `mem_limit`。
- **Boot 4 的 tracing 属性名改过一次，而且废弃级别是 `error`。** 老教程写的
  `management.zipkin.tracing.endpoint` 现在**直接启动失败**；真名是
  `management.tracing.export.zipkin.endpoint`。
- **`core.autocrlf=true` 会把 `\n` 悄悄换成 `\r\n`。** shell 脚本到 Linux 上就报
  `bad interpreter: ...^M`，而**本地跑得通、`git status` 也不提示**。用 `.gitattributes` 锁死。
- **多轮对话里 `prompt tokens` 是一轮比一轮大的**——因为每轮都要把整个上下文重发一遍。
  只看总数你不知道钱是怎么花的，所以才要**逐轮**记账。

---

## 相关

- **业务系统本体**：hmdp（黑马点评，Redis 实战）—— 独立仓库，本服务通过 HTTP 调它
- **姊妹项目 bili-rag**：Spring Boot 4 + Spring AI 2.0 的 RAG 服务
  （把 B 站视频字幕做成可检索的知识库，是这个项目之外的另一条技术线）

# 部署手册

把这个项目从"只在我电脑上能跑"变成"有个网址能访问"。

一共 5 个容器，`docker-compose.yml` 一个文件全管了：

```
                    公网 :8082                    127.0.0.1:9411（只本机）
                        │                               │
   ┌────────────────────┴───────────────────────────────┴──────────┐
   │                     Docker 内网 hmdp-net                        │
   │                                                                │
   │   ┌──────────┐        ┌────────┐        ┌───────┐              │
   │   │ hmdp-ai  │───────▶│  hmdp  │───────▶│ mysql │              │
   │   │  :8082   │  HTTP  │ :8081  │  JDBC  │ :3306 │              │
   │   └────┬─────┘        └───┬────┘        └───────┘              │
   │        │                  │                                    │
   │        │                  └──────────▶ redis :6379             │
   │        ▼                                                       │
   │   ┌──────────┐                                                 │
   │   │  zipkin  │  :9411                                          │
   │   └──────────┘                                                 │
   └────────────────────────────────────────────────────────────────┘
```

两个刻意的设计（写简历可以讲）：

1. **hmdp（8081）不映射到宿主机** —— 它没有鉴权又带着秒杀下单接口，让它只活在容器内网、
   只被 hmdp-ai 调用，外面根本连不到。
2. **zipkin（9411）只绑 127.0.0.1** —— 链路数据是内部流水，不裸奔在公网，要看就先开 SSH 隧道。

---

## 0. 预备：服务器要买什么样的

| 项 | 要求 | 说明 |
|---|---|---|
| 内存 | **2G 勉强，4G 舒服** | 3 个 JVM（hmdp / hmdp-ai / zipkin）+ MySQL。2G 也能跑，但 zipkin 一开就紧 |
| 系统 | **Ubuntu 22.04 或 24.04** | 本手册的命令按 Ubuntu 写。别的发行版 apt 换成 yum |
| 带宽 | 1~3 Mbps 够了 | 只是你和面试官点几下，不跑流量 |
| 地域 | 随便，国内更好 | 因为要调 `api.deepseek.com`，海外机器反而慢 |

买的时候**记下公网 IP 和 root 密码**，后面全要用。

---

## 1. 本地打包

jar 在**你本机**打，服务器只负责跑（原因见 `Dockerfile.hmdp` 里的注释）。

开两个终端，或者一条条来：

```bash
# ① hmdp（黑马点评后端）
mvn -f /d/Java/code/hmdp/pom.xml clean package -DskipTests

# ② hmdp-ai（本服务）
mvn -f /d/Java/code/hmdp-ai/pom.xml clean package -DskipTests
```

> **`clean` 不能省。** 之前踩过一次：`target/classes` 里混进了 Java 21 编的 class，
> 而 hmdp 是 Spring Boot 2.7.4，它的 ASM 认不出高版本字节码，
> 一启动就是 `Unsupported class file major version 65`。`clean` 就是清掉这些陈年垃圾。

### ★ 打完必须验一下字节码版本（30 秒，能省你一小时）

```bash
unzip -p /d/Java/code/hmdp/target/hmdp-1.0-SNAPSHOT.jar \
  BOOT-INF/classes/com/hmdp/HmDianPingApplication.class \
  | od -An -tu1 -j6 -N2 | awk '{print $2}'
```

- 输出 **`52`** → **对**（52 = Java 8）
- 输出 **`65`** → **错**，说明编译目标没生效。回去查 pom 的 `maven.compiler.source/target`，
  或者 IDEA 的 Project SDK 把 target 目录写成了 Java 21

> 这条命令在干什么：`.class` 文件前 8 个字节是固定格式 ——
> 前 4 字节是魔数 `CAFEBABE`，第 5~6 字节是 minor，**第 7~8 字节才是版本号**。
> `-j6 -N2` 就是从第 6 个字节开始读 2 个字节。52 是十六进制 `0x34` = Java 8，
> 65 是 `0x41` = Java 21。
> （我第一次写这条命令时把偏移写成了 8，读出来是常量池个数 32，白高兴一场 ——
> 数字看着像但还是得对得上"应该是多少"，不然等于没验。）

### 把 jar 摆到 deploy 目录

```bash
cp /d/Java/code/hmdp/target/hmdp-1.0-SNAPSHOT.jar         /d/Java/code/hmdp-ai/deploy/jars/hmdp.jar
cp /d/Java/code/hmdp-ai/target/hmdp-ai-0.0.1-SNAPSHOT.jar /d/Java/code/hmdp-ai/deploy/jars/hmdp-ai.jar

ls -lh /d/Java/code/hmdp-ai/deploy/jars/
# 应该看到两个 jar：hmdp 约 46MB，hmdp-ai 约 112MB
```

---

## 2. 传到服务器

```bash
# 用户名@你的公网IP，会提示输密码
scp -r /d/Java/code/hmdp-ai/deploy root@<服务器IP>:/opt/hmdp-ai
```

传完登上去看看：

```bash
ssh root@<服务器IP>
ls -la /opt/hmdp-ai/deploy/jars/
```

**`.env` 此刻还不存在，这是对的** —— 密钥不该出现在你本机的这个目录里，
下一步在服务器上现填。

---

## 3. 服务器上装 Docker

如果你买的是"已装 Docker"的镜像，直接跳到第 4 步。

```bash
# 用阿里云镜像装，比从官方源装快很多
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
```

装完确认是 **Compose v2**（命令是 `docker compose`，中间有空格，**不是** `docker-compose`）：

```bash
docker compose version
# 期望输出类似 Docker Compose version v2.29.x
```

> ⚠️ 如果这条报 "command not found"，说明只装了老版 v1。
> `docker-compose.yml` 里用了 v2 才支持的语法（`name:`、`depends_on.condition`），
> 必须装上 v2 插件：
> ```bash
> apt-get update && apt-get install -y docker-compose-plugin
> ```

---

## 4. 配镜像加速 + 开端口

### 4a. 镜像加速（不配的话拉镜像能等到睡着）

进阿里云控制台 → **容器镜像服务 ACR** → 左侧「镜像工具」→「镜像加速器」，
复制给你分配的那个 `https://xxxx.mirror.aliyuncs.com` 地址，然后：

```bash
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://你的专属地址.mirror.aliyuncs.com"]
}
EOF

systemctl restart docker
docker info | grep -A3 "Registry Mirrors"     # 确认配置生效
```

> 加速器地址是**跟着你阿里云账号走的、免费的**，比任何公共镜像站都稳。
> 网上抄来的公共地址（各种 `docker.xxx.com`）经常头一天还能用第二天就挂，别指望。

### 4b. 开端口 ★ 最容易忘的一步 ★

**① 云控制台的安全组**（这一层最容易漏，漏了就是"服务明明起来了但外面连不上"）

阿里云/腾讯云控制台 → 你的实例 → **安全组** → 入方向 → 添加规则：

| 协议 | 端口 | 来源 |
|---|---|---|
| TCP | **8082** | 0.0.0.0/0 |

**② 服务器自己的防火墙**

```bash
ufw status              # 如果显示 inactive，这一步就结束了
ufw allow 8082/tcp      # 如果 ufw 是开着的
```

> **9411 不要放行。** 它是刻意绑在 127.0.0.1 上的，只走 SSH 隧道（下面第 7 步）。

---

## 5. 填密钥

```bash
cd /opt/hmdp-ai/deploy
cp .env.example .env
vi .env
```

三个值必须填：

| 变量 | 填什么 |
|---|---|
| `MYSQL_ROOT_PASSWORD` | 自己编一串字母数字。**别用 `$` 或引号** |
| `DEEPSEEK_API_KEY` | 抄你本地「启动.bat」里的那个（形如 `sk-...`），别新建 |
| `HMDP_AI_API_KEY` | 另编一长串，和上面两个都不一样。**不能留空**，空着服务直接拒绝启动 |

不会编随机串的话：

```bash
openssl rand -hex 16
```

改完确认一下**没有尖括号/占位符残留**：

```bash
grep -c "CHANGE_ME" .env      # 必须是 0
```

> `.env` 已经在 `.gitignore` 里了，不会被提交。
> 但也别因为这样就放松 —— 重点是**永远不要**把真 key 写进 `docker-compose.yml`。

---

## 6. 启动，然后验证

```bash
cd /opt/hmdp-ai/deploy
docker compose up -d --build
```

第一次要拉镜像 + 构建，国内配了加速器大概 **3~8 分钟**。看进度：

```bash
docker compose ps
```

五个容器都该是 `running`（mysql 是 `healthy`）。

### 按顺序验这 4 件事

**① 容器都活着**

```bash
docker compose ps
```

**② 服务真的起来了**（注意：actuator 也被 ApiKeyFilter 拦着，要带钥匙）

```bash
KEY=$(grep '^HMDP_AI_API_KEY=' .env | cut -d= -f2-)
curl -s -H "X-Api-Key: $KEY" http://127.0.0.1:8082/actuator/health
# 期望：{"status":"UP"}
```

这样写是为了**不让 key 出现在屏幕上**（history、截图、录屏里都没有）。

**③ 数据库表建好了**

```bash
source .env
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "use hmdp; show tables;"
```

应该列出 `tb_blog` `tb_shop` `tb_user` `tb_voucher` 等一堆表。
**没有表** → 初始化脚本没跑，见下面故障表。

**④ 端到端跑一次**（这一步会真花掉几分钱，但值得）

```bash
KEY=$(grep '^HMDP_AI_API_KEY=' .env | cut -d= -f2-)
curl -s -G -H "X-Api-Key: $KEY" http://127.0.0.1:8082/ai/agent \
  --data-urlencode "question=帮我找找有什么好吃的"
```

> 为什么要 `-G --data-urlencode`：问题里有中文，直接拼进 URL 在某些终端下会乱码。
> 让 curl 自己编码，省心。

拿到一段像样的回答，说明 **AI → hmdp → MySQL/Redis 整条链路通了**。

**⑤ 换成从外网访问**

用浏览器打开：

```
http://<服务器IP>:8082/ai/agent?question=有什么好吃的
```

会返回 **401** —— **这是对的，说明保护在生效**。
要带 key 才能调，说明这个接口没有裸奔。

---

## 7. 看链路追踪（Zipkin）

9411 只绑在服务器本机，所以要先开一条 SSH 隧道：

```bash
# 在你【本机】的 Git Bash 里跑，不要关这个窗口
ssh -L 9411:127.0.0.1:9411 root@<服务器IP>
```

保持这个 SSH 窗口开着，然后在**本机浏览器**打开：

```
http://localhost:9411
```

> 原理一句话：`-L 9411:127.0.0.1:9411` = "把我本机的 9411 端口，
> 接到服务器上它自己的 9411"。流量全程在 SSH 加密通道里走，
> 服务器不用对公网开这个端口。查生产数据库的界面、内部管理后台，平时都是这么连的。

进了界面：Service Name 选 `hmdp-ai` → 点 **RUN QUERY** → 点进那条最长的时间条。

**刚启动时这里可能是空的** —— 因为 Zipkin 存内存，重启就清空，
而且只有你发过请求才会产生链路。先在第 6 步跑一次请求再来看。

---

## 8. 出问题了照这张表查

先看日志，八成的答案都在里面：

```bash
cd /opt/hmdp-ai/deploy
docker compose logs hmdp-ai --tail 50
docker compose logs hmdp    --tail 50
```

| 现象 | 大概是什么 | 怎么办 |
|---|---|---|
| 浏览器打不开 `IP:8082` | **云控制台安全组没放行 8082** | 回去做第 4b 步 ① |
| 同上 | 服务器 ufw 挡着 | `ufw allow 8082/tcp` |
| `docker compose up` 卡在 pull 不动 | 拉 Docker Hub 慢/被墙 | 回去做第 4a 步，配加速器 |
| 报 `Unsupported class file major version 65` | jar 是 Java 21 字节码 | 回第 1 步重打包 + 走校验命令 |
| hmdp 反复重启 | 连不上 MySQL/Redis，或配置名写错 | `docker compose logs hmdp \| tail -50`，重点看 `Connection refused` |
| 报 `No database selected` / 表不存在 | 初始化 SQL 没跑（卷里已经有旧数据了） | `docker compose down -v` 再 `up -d --build`（**-v 会清库，确认能重来**） |
| hmdp-ai 起不来，日志里提到 api key | `.env` 少了 `DEEPSEEK_API_KEY`，或 `HMDP_AI_API_KEY` 是空的 | `grep CHANGE_ME .env` 应为 0 |
| hmdp-ai 能起来，一问就 500 | DeepSeek key 不对 / 余额没了 | `docker compose logs hmdp-ai \| tail -50` |
| AI 回答"没找到店铺" | hmdp-ai 连不上 hmdp | 见下面那条排查命令 |
| Zipkin 里一直空的 | 上报地址没被覆盖成 `http://zipkin:9411` | `docker compose exec hmdp-ai env \| grep -i zipkin` |
| hmdp 报奇怪的反射 / Unsafe / Netty 错 | Java 17 和它 2020 年的 redisson 3.13.6 不对付 | 把 `Dockerfile.hmdp` 第一行换成 `eclipse-temurin:8-jre`，`docker compose up -d --build hmdp` |
| 机器卡死 / 连接超时 | 4G 内存跑 3 个 JVM 撑不住 | `docker stats --no-stream` 看谁吃满了 |

**懒得一条条查？** 这个脚本会按依赖顺序把上面这些自动过一遍，断在哪环直接报出来：

```bash
cd /opt/hmdp-ai/deploy && bash smoke-test.sh
```

它会自己从 `.env` 读 key（不会让密钥出现在命令行里），最后打印"通过 N 项、失败 M 项"，
失败的那几行就是你要去查的方向。

**hmdp-ai 到底连不连得上 hmdp？** 用一次性容器在同一个内网里探一下：

```bash
docker run --rm --network hmdp-net curlimages/curl:latest \
  -s http://hmdp:8081/shop-type/list | head -c 300
```

返回一串 JSON（店铺分类列表）就是通的。

---

## 9. 以后改了代码要重新上线

```bash
# ① 本机：重新打包，覆盖 jar
mvn -f /d/Java/code/hmdp-ai/pom.xml clean package -DskipTests
cp /d/Java/code/hmdp-ai/target/hmdp-ai-0.0.1-SNAPSHOT.jar /d/Java/code/hmdp-ai/deploy/jars/hmdp-ai.jar

# ② 本机：只传这一个 jar
scp /d/Java/code/hmdp-ai/deploy/jars/hmdp-ai.jar root@<服务器IP>:/opt/hmdp-ai/deploy/jars/

# ③ 服务器：只重建这一个服务，别的容器不动
cd /opt/hmdp-ai/deploy
docker compose up -d --build hmdp-ai
```

MySQL 和 Redis 的数据都在命名卷里，`up -d` 不会碰它们。
（只有 `down -v` 里的 `-v` 才会删卷。）

---

## 10. 收工：怎么停 / 怎么彻底清掉

```bash
cd /opt/hmdp-ai/deploy

docker compose stop              # 停，容器还在，下次 start 就回来
docker compose down              # 删容器和网络，数据卷保留
docker compose down -v           # ★ 连数据卷一起删（数据库、H2 记忆全没，慎用）
docker system prune -a           # 清掉没用的镜像，能把磁盘收回来好几个 G
```

> 试用期的云服务器到期前记得**把数据先 dump 出来**，机器一释放就找不回来了：
> ```bash
> source .env
> docker compose exec mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" hmdp > ~/hmdp-backup.sql
> ```

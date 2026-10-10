#!/usr/bin/env bash
# =============================================================================
#  部署自检 —— 在服务器上、deploy 目录里跑：
#
#      cd /opt/hmdp-ai/deploy && bash smoke-test.sh
#
#  它按依赖顺序把整条链路走一遍，每一环单独报 OK / FAIL，
#  断在哪一环，第 8 节故障表里就查那一行。
#
#  ★ 脚本自己从 .env 里读 key，不让你往命令行里贴 ——
#    命令行会进 history，截图和录屏里也会留痕。
# =============================================================================

# 不用 set -e：某一环失败要接着往下查，而不是整个脚本直接退出。
cd "$(dirname "$0")" || exit 1

PASS=0
FAIL=0
FAILED_STEPS=()

ok()   { echo "  [ OK ] $1"; PASS=$((PASS + 1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL + 1)); FAILED_STEPS+=("$1"); }

echo "=============================================="
echo " hmdp-ai 部署自检"
echo "=============================================="
echo

# ---------------------------------------------------------------- 0. 前置 ---
echo "[0] 环境"

if [ ! -f .env ]; then
    bad ".env 不存在（cp .env.example .env 然后填值）"
    echo "      后面全都要用 .env，先把它建好再跑。"
    exit 1
fi
ok ".env 存在"

if grep -q "CHANGE_ME" .env; then
    bad ".env 里还有 CHANGE_ME 占位符没改"
    grep -n "CHANGE_ME" .env | sed 's/=.*/=.../'   # 只打印变量名，不打印值
else
    ok ".env 已填过真值（无占位符残留）"
fi

if ! docker compose version >/dev/null 2>&1; then
    bad "docker compose (v2) 不可用"
    echo "      装：apt-get update && apt-get install -y docker-compose-plugin"
    exit 1
fi
ok "docker compose 可用"

# 取值：用 grep/cut 而不是 source .env。
# source 会把里面的值当 shell 代码执行，密码里有个 $ 或反引号就出乱子。
getenv() { grep -E "^$1=" .env | head -1 | cut -d= -f2- | tr -d '\r'; }

KEY=$(getenv HMDP_AI_API_KEY)
DBPW=$(getenv MYSQL_ROOT_PASSWORD)
PORT=$(getenv PUBLIC_PORT); PORT=${PORT:-8082}

if [ -z "$KEY" ]; then
    bad "HMDP_AI_API_KEY 是空的（不能为空，服务会拒绝启动）"
fi
echo

# ------------------------------------------------------------ 1. 容器状态 ---
echo "[1] 容器状态"

RUNNING=$(docker compose ps --status running --services 2>/dev/null | sort)
echo "      跑着的：$(echo "$RUNNING" | tr '\n' ' ')"

for svc in mysql redis hmdp hmdp-ai zipkin; do
    if echo "$RUNNING" | grep -qx "$svc"; then
        ok "$svc 运行中"
    else
        bad "$svc 没在跑 →  docker compose logs $svc --tail 30"
    fi
done
echo

# ------------------------------------------------------------ 2. 健康检查 ---
echo "[2] hmdp-ai 健康检查"

CODE=$(curl -s -o /tmp/_health.json -w "%{http_code}" -m 15 \
        -H "X-Api-Key: $KEY" "http://127.0.0.1:$PORT/actuator/health")

case "$CODE" in
    200) ok "health 返回 200：$(cat /tmp/_health.json)" ;;
    401) bad "401 —— key 对不上。检查 .env 的 HMDP_AI_API_KEY 和容器里的是不是同一个
             docker compose exec hmdp-ai env | grep HMDP_AI_API_KEY" ;;
    000) bad "连不上 127.0.0.1:$PORT —— 容器可能刚起还在初始化，等 30 秒重跑" ;;
    *)   bad "health 返回 HTTP $CODE →  docker compose logs hmdp-ai --tail 30" ;;
esac
echo

# --------------------------------------------------------------- 3. 数据库 ---
echo "[3] 数据库表"

if docker compose exec -T mysql mysql -uroot -p"$DBPW" -N -B \
     -e "select count(*) from information_schema.tables where table_schema='hmdp';" \
     >/tmp/_tbl.txt 2>/tmp/_tbl.err; then
    N=$(tr -d '\r\n' < /tmp/_tbl.txt)
    if [ "${N:-0}" -gt 5 ] 2>/dev/null; then
        ok "hmdp 库里建了 $N 张表"
    else
        bad "hmdp 库里只有 $N 张表，初始化 SQL 大概没跑
             修：docker compose down -v && docker compose up -d --build
             （-v 会清空数据库，确认能从头来过再执行）"
    fi
else
    bad "连不上 MySQL 或查不了 →  docker compose logs mysql --tail 30"
    sed 's/^/       /' /tmp/_tbl.err 2>/dev/null | head -5
fi
echo

# --------------------------------------------------- 4. hmdp-ai → hmdp 连通 ---
echo "[4] hmdp-ai 能不能调到 hmdp"

# 用一个一次性容器在【同一个 docker 内网】里探，最接近 hmdp-ai 的真实视角。
# 从宿主机是探不到的 —— hmdp 刻意没对外开端口。
BODY=$(docker run --rm --network hmdp-net curlimages/curl:latest \
       -s -m 15 http://hmdp:8081/shop-type/list 2>/dev/null | head -c 200)

if echo "$BODY" | grep -q '"data"'; then
    ok "hmdp 应答正常（/shop-type/list 返回了 JSON）"
elif [ -n "$BODY" ]; then
    bad "hmdp 有应答但内容不对：$BODY"
else
    bad "拿不到 hmdp 的应答 →  docker compose logs hmdp --tail 50"
fi
echo

# ------------------------------------------------------------ 5. 端到端 ---
echo "[5] 端到端：真的问一句"

# -G --data-urlencode 让 curl 自己编码中文，别手工往 URL 里拼。
ANSWER=$(curl -s -m 90 -G -H "X-Api-Key: $KEY" \
         "http://127.0.0.1:$PORT/ai/recommend" \
         --data-urlencode "keyword=火锅" 2>/dev/null)

if [ -z "$ANSWER" ]; then
    bad "没有应答（可能超时）→  docker compose logs hmdp-ai --tail 50"
elif echo "$ANSWER" | grep -qi "error\|exception"; then
    bad "返回里像是有错误：$(echo "$ANSWER" | head -c 200)"
else
    ok "AI 正常应答，前 160 字："
    echo "$ANSWER" | head -c 160 | sed 's/^/       /'
    echo
fi
echo

# ---------------------------------------------------------------- 6. 链路 ---
echo "[6] 链路追踪"

if docker compose exec -T hmdp-ai printenv MANAGEMENT_TRACING_EXPORT_ZIPKIN_ENDPOINT 2>/dev/null \
   | grep -q "zipkin:9411"; then
    ok "上报地址指向 zipkin 容器（不是 localhost）"
else
    bad "上报地址不对 —— 容器里的 localhost 是它自己，链路会全丢"
fi

if docker compose exec -T zipkin wget -q -O- "http://127.0.0.1:9411/api/v2/services" 2>/dev/null \
   | grep -q "hmdp-ai"; then
    ok "Zipkin 里已经有 hmdp-ai 的链路数据了"
else
    echo "  [ -- ] Zipkin 里还没有 hmdp-ai（正常：刚起步、或刚才那条请求采样没落在窗口内）"
    echo "         再看：先开 SSH 隧道 → http://localhost:9411 → Service Name 选 hmdp-ai → RUN QUERY"
fi
echo

# -------------------------------------------------------------- 汇总 ---
echo "=============================================="
echo " 通过 $PASS 项，失败 $FAIL 项"
if [ "$FAIL" -gt 0 ]; then
    echo
    echo " 断了这些："
    for s in "${FAILED_STEPS[@]}"; do
        echo "   - $s"
    done
    echo
    echo " 拿最后那行英文去查 deploy/README.md 第 8 节的故障表。"
fi
echo "=============================================="

rm -f /tmp/_health.json /tmp/_tbl.txt /tmp/_tbl.err
[ "$FAIL" -eq 0 ] || exit 1

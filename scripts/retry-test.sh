#!/bin/bash
# ============================================================
# 用户可见 + 重试接口 测试
#
# 用法：  bash scripts/retry-test.sh
# 日志：  taskflow-center/target/retry-test.log
#
# 设计要点：把"疑似丢失"的判定做成【用户可见 + 用户可处置】，
#   而不是让系统自动重投 —— 这样"误判"的代价从"系统往队列里塞重复任务"
#   降级成"用户多点一次（由 CAS 挡住，无害）"，所以判定可以更激进。
#
# 【1】查询接口暴露运行态：suspectedLost / stuckSeconds
# 【2】正常排队时【不允许】重试（防用户把队列点满）
# 【3】重试生效：卡住的任务被重置并跑完
# 【4】终态任务不允许重试
# ============================================================
set -u

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CENTER_DIR="$PROJECT_DIR/taskflow-center"
JAR="$CENTER_DIR/target/taskflow-center-1.0.0-SNAPSHOT.jar"
LOG="$CENTER_DIR/target/retry-test.log"

API=http://localhost:8081/api/task
MYSQL_BIN="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql"
DB_USER=root
DB_PASS=jinjian20010210
DB_NAME=taskflow

STUCK_ID=8800000000000000100    # 模拟"卡在待质检"的任务 id
FAIL=0

if [ ! -f "$JAR" ]; then
  echo "❌ 找不到 $JAR，请先 mvn -pl taskflow-engine-local,taskflow-center -am package -DskipTests"
  exit 1
fi

mysql_q() { "$MYSQL_BIN" -N -B --default-character-set=utf8mb4 -u"$DB_USER" -p"$DB_PASS" -D "$DB_NAME" -e "$1" 2>/dev/null; }

status_name() {
  case "$1" in
    0) echo CREATED;; 1) echo QUEUED;; 2) echo TRANSCRIBING;; 3) echo TRANSCRIBED;;
    4) echo QC_ING;; 5) echo SUCCESS;; 6) echo FAILED;; 7) echo TIMEOUT;; 8) echo CANCELED;;
    *) echo UNKNOWN;;
  esac
}

stop_app() {
  local pid
  pid=$(netstat -ano 2>/dev/null | grep ":8081" | grep -i listen | awk '{print $NF}' | head -1)
  [ -n "$pid" ] && taskkill //F //PID "$pid" >/dev/null 2>&1
  sleep 1
}

start_app() {
  local mark
  touch "$LOG"
  mark=$(wc -l < "$LOG")
  cd "$CENTER_DIR" || exit 1
  java -jar "$JAR" "$@" >> "$LOG" 2>&1 &
  local i
  for i in $(seq 1 40); do
    tail -n +$((mark + 1)) "$LOG" | grep -q "Started TaskFlowCenterApplication" && break
    sleep 1
  done
  for i in $(seq 1 30); do
    tail -n +$((mark + 1)) "$LOG" | grep -qE "引擎重建完成" && return 0
    sleep 1
  done
  return 1
}

json_field() {   # $1=json $2=字段名
  sed -n "s/.*\"$2\":\([^,}]*\).*/\1/p" <<< "$1"
}

echo "=== 准备：清理数据与端口 ==="
stop_app
mysql_q "DELETE FROM task WHERE request_id LIKE 'retry-%';"

echo "=== 启动应用（suspected-lost-stuck=5s；transcribed-stuck 调大以免补偿抢先处理）==="
rm -f "$LOG"
if ! start_app --taskflow.pipeline.suspected-lost-stuck-seconds=5 \
               --taskflow.pipeline.transcribed-stuck-seconds=3600; then
  echo "❌ 启动失败"; tail -20 "$LOG"; stop_app; exit 1
fi
echo "✅ 应用已启动"

# ============================================================
# 【1】查询接口暴露运行态
# ============================================================
echo ""
echo "############ 【1】查询接口暴露运行态 ############"
"$MYSQL_BIN" --default-character-set=utf8mb4 -u"$DB_USER" -p"$DB_PASS" -D "$DB_NAME" -e \
  "INSERT INTO task (id, request_id, batch_id, task_type, params, status, priority,
                     retry_count, max_retry, result, create_time, update_time, deleted)
   VALUES ($STUCK_ID, 'retry-stuck-001', NULL, 'MOCK_TASK', '{\"msg\":\"stuck\"}',
           3, 1, 0, 3, '{\"transcript\":\"already-done\"}',
           DATE_SUB(NOW(), INTERVAL 10 MINUTE), DATE_SUB(NOW(), INTERVAL 5 MINUTE), 0);" 2>&1 | grep -v "Using a password"

if [ "$(mysql_q "SELECT COUNT(*) FROM task WHERE id=$STUCK_ID;")" != "1" ]; then
  echo "❌ INSERT 未生效"; stop_app; exit 1
fi
echo "已插入一条卡在 TRANSCRIBED 的任务（update_time = 5 分钟前），taskId=$STUCK_ID"

DETAIL=$(curl -s "$API/$STUCK_ID")
echo "GET /api/task/$STUCK_ID →"
echo "$DETAIL" | sed 's/^/    /'
SUSPECT=$(json_field "$DETAIL" suspectedLost)
STUCK=$(json_field "$DETAIL" stuckSeconds)
if [ "$SUSPECT" = "true" ]; then
  echo "✅ 【1】运行态可见：suspectedLost=$SUSPECT, stuckSeconds=$STUCK"
else
  echo "❌ 【1】suspectedLost=$SUSPECT（期望 true）"
  FAIL=1
fi

# ============================================================
# 【2】正常排队时不允许重试
# ============================================================
echo ""
echo "############ 【2】正常排队时不允许重试 ############"
RESP=$(curl -s -X POST "$API/submit" -H "Content-Type: application/json" \
  -d '{"requestId":"retry-normal-001","taskType":"MOCK_TASK","priority":1,"params":{"msg":"normal"}}')
echo "提交普通任务: $RESP"
NORMAL_ID=$(echo "$RESP" | sed -n 's/.*"taskId":"\([0-9]*\)".*/\1/p')

RETRY_RESP=$(curl -s -X POST "$API/$NORMAL_ID/retry")
echo "立刻重试 → $RETRY_RESP"
CODE=$(json_field "$RETRY_RESP" code)
if [ "$CODE" = "40004" ]; then
  echo "✅ 【2】正常排队被正确拒绝（40004 任务仍在正常排队）"
else
  echo "❌ 【2】code=$CODE（期望 40004）"
  FAIL=1
fi

echo "--- 等它自己跑完 ---"
for _ in $(seq 1 15); do
  [ "$(mysql_q "SELECT status FROM task WHERE id=$NORMAL_ID;")" = "5" ] && break
  sleep 1
done
echo "普通任务最终状态: $(status_name "$(mysql_q "SELECT status FROM task WHERE id=$NORMAL_ID;")")"

# ============================================================
# 【3】重试生效
# ============================================================
echo ""
echo "############ 【3】重试生效（卡住的任务被重置并跑完）############"
RETRY_RESP=$(curl -s -X POST "$API/$STUCK_ID/retry")
echo "POST /api/task/$STUCK_ID/retry → $RETRY_RESP"
CODE=$(json_field "$RETRY_RESP" code)
if [ "$CODE" = "0" ]; then
  echo "✅ 【3】重试请求被接受"
else
  echo "❌ 【3】code=$CODE（期望 0）"
  FAIL=1
fi

for i in $(seq 1 15); do
  CODE_S=$(mysql_q "SELECT status FROM task WHERE id=$STUCK_ID;")
  printf "  t=%ss status=%s (%s)\n" "$i" "${CODE_S:-?}" "$(status_name "${CODE_S:-}")"
  [ "${CODE_S:-}" = "5" ] && break
  sleep 1
done

if [ "$(mysql_q "SELECT status FROM task WHERE id=$STUCK_ID;")" = "5" ]; then
  echo "✅ 【3】重试生效：卡住的任务被重置并执行到 SUCCESS"
else
  echo "❌ 【3】重试失败：最终状态 = $(status_name "$(mysql_q "SELECT status FROM task WHERE id=$STUCK_ID;")")"
  FAIL=1
fi

# ============================================================
# 【4】终态任务不允许重试
# ============================================================
echo ""
echo "############ 【4】终态任务不允许重试 ############"
RETRY_RESP=$(curl -s -X POST "$API/$STUCK_ID/retry")
echo "对已完成任务重试 → $RETRY_RESP"
CODE=$(json_field "$RETRY_RESP" code)
if [ "$CODE" = "40005" ]; then
  echo "✅ 【4】终态被正确拒绝（40005 任务已结束，无需重试）"
else
  echo "❌ 【4】code=$CODE（期望 40005）"
  FAIL=1
fi

echo ""
echo "  关键日志："
grep -E "用户重试任务|用户请求重试任务" "$LOG" | tail -3 | sed 's/^/    /'

echo ""
echo "========== 停止应用 =========="
stop_app
if [ "$FAIL" = "0" ]; then
  echo "✅ 全部通过。完整日志：$LOG"
else
  echo "❌ 存在失败项，请查看日志：$LOG"
  exit 1
fi

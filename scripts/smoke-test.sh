#!/bin/bash
# ============================================================
# 本地引擎端到端冒烟测试
#
# 用法：  bash scripts/smoke-test.sh
# 产物：  taskflow-center/target/smoke-test.log （target/ 已被 .gitignore 忽略）
#
# 覆盖链路：
#   1) VIP 任务        smoke-vip-001     priority=10  → SUCCESS
#   2) NORMAL 任务     smoke-nor-001     priority=1   → SUCCESS
#   3) 幂等            重复提交同一 requestId → 同一 taskId 且 duplicated=true
#   4) 慢任务          slow-001          转写3s/质检2s → 用于观测中间状态链
#   5) 转写失败        fail-001                         → FAILED
#   6) 质检失败        qcfail-001                       → FAILED
#
# 状态码：0 CREATED 1 QUEUED 2 TRANSCRIBING 3 TRANSCRIBED 4 QC_ING 5 SUCCESS 6 FAILED
# ============================================================
set -u

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CENTER_DIR="$PROJECT_DIR/taskflow-center"
JAR="$CENTER_DIR/target/taskflow-center-1.0.0-SNAPSHOT.jar"
LOG="$CENTER_DIR/target/smoke-test.log"

API=http://localhost:8081/api/task
MYSQL_BIN="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql"
DB_USER=root
DB_PASS=jinjian20010210
DB_NAME=taskflow

if [ ! -f "$JAR" ]; then
  echo "❌ 找不到 $JAR，请先执行："
  echo "   mvn -pl taskflow-engine-local,taskflow-center -am package -DskipTests"
  exit 1
fi

mysql_q() {
  "$MYSQL_BIN" -N -B --default-character-set=utf8mb4 \
    -u"$DB_USER" -p"$DB_PASS" -D "$DB_NAME" -e "$1" 2>/dev/null
}

status_name() {
  case "$1" in
    0) echo CREATED;; 1) echo QUEUED;; 2) echo TRANSCRIBING;; 3) echo TRANSCRIBED;;
    4) echo QC_ING;; 5) echo SUCCESS;; 6) echo FAILED;; 7) echo TIMEOUT;; 8) echo CANCELED;;
    *) echo UNKNOWN;;
  esac
}

submit() {   # $1=requestId  $2=priority
  curl -s -X POST "$API/submit" -H "Content-Type: application/json" \
    -d "{\"requestId\":\"$1\",\"taskType\":\"MOCK_TASK\",\"priority\":$2,\"params\":{\"msg\":\"$1\"}}"
}

extract_id() {  # 从提交响应里取 taskId
  sed -n 's/.*"taskId":"\([0-9]*\)".*/\1/p'
}

stop_app() {
  local pid
  pid=$(netstat -ano 2>/dev/null | grep ":8081" | grep -i listen | awk '{print $NF}' | head -1)
  if [ -n "$pid" ]; then
    taskkill //F //PID "$pid" >/dev/null 2>&1
    sleep 1
  fi
}

echo "=== 清理旧数据 & 端口 ==="
stop_app
mysql_q "DELETE FROM task WHERE request_id LIKE 'smoke-%' OR request_id LIKE 'slow-%' OR request_id LIKE 'fail-%' OR request_id LIKE 'qcfail-%';"

echo "=== 启动应用 ==="
rm -f "$LOG"
cd "$CENTER_DIR" || exit 1
# 打开 engine.local.stage 的 DEBUG，以便记录【状态流转】审计日志（QUEUED/TRANSCRIBED 是毫秒级交接态，只能从日志看）
java -jar "$JAR" --logging.level.com.taskflow.engine.local.stage=DEBUG > "$LOG" 2>&1 &
for _ in $(seq 1 40); do
  grep -q "Started TaskFlowCenterApplication" "$LOG" 2>/dev/null && break
  sleep 1
done
if ! grep -q "Started TaskFlowCenterApplication" "$LOG" 2>/dev/null; then
  echo "❌ 应用启动失败，日志尾部："
  tail -20 "$LOG"
  stop_app
  exit 1
fi
echo "✅ 应用已启动"

# ---------------- 提交 ----------------
echo ""
echo "========== 1) VIP 任务 (priority=10) =========="
RESP=$(submit smoke-vip-001 10); echo "$RESP"; VIP_ID=$(echo "$RESP" | extract_id)

echo "========== 2) NORMAL 任务 (priority=1) =========="
RESP=$(submit smoke-nor-001 1); echo "$RESP"; NOR_ID=$(echo "$RESP" | extract_id)

echo "========== 3) 幂等：重复提交 smoke-vip-001 =========="
submit smoke-vip-001 10; echo ""

echo "========== 4) 慢任务 slow-001 (转写3s / 质检2s) =========="
RESP=$(submit slow-001 1); echo "$RESP"; SLOW_ID=$(echo "$RESP" | extract_id)

echo "========== 5) 转写失败 fail-001 =========="
RESP=$(submit fail-001 1); echo "$RESP"; FAIL_ID=$(echo "$RESP" | extract_id)

echo "========== 6) 质检失败 qcfail-001 =========="
RESP=$(submit qcfail-001 1); echo "$RESP"; QCFAIL_ID=$(echo "$RESP" | extract_id)

# ---------------- 观测状态链 ----------------
echo ""
echo "========== 7) 观测慢任务状态链（每 300ms 采样一次，只打印变化）=========="
if [ -n "$SLOW_ID" ]; then
  PREV=""
  for i in $(seq 0 26); do
    CODE=$(curl -s "$API/$SLOW_ID" | sed -n 's/.*"status":\([0-9]*\).*/\1/p')
    if [ "${CODE:-}" != "$PREV" ]; then
      printf "  t≈%sms  status=%s (%s)\n" "$((i * 300))" "${CODE:-?}" "$(status_name "${CODE:-}")"
      PREV="${CODE:-}"
    fi
    sleep 0.3
  done
else
  echo "  (未拿到 taskId，跳过)"
fi

sleep 1

# ---------------- 最终结果 ----------------
echo ""
echo "========== 8.1) 状态流转审计（slow-001 完整链路）=========="
grep -F "状态流转" "$LOG" | head -20

echo ""
echo "========== 8.2) 引擎关键日志 =========="
grep -E "任务已入队|入口桶已满|抢.*失败|转写失败|质检失败|任务完成|已死亡|疑似停滞|ERROR|WARN" "$LOG" | tail -25

echo ""
echo "========== 9) 最终状态 =========="
mysql_q "SELECT request_id, priority, status, LEFT(COALESCE(fail_reason,''), 30) AS fail_reason, LEFT(COALESCE(result,''), 34) AS result
         FROM task WHERE request_id LIKE 'smoke-%' OR request_id LIKE 'slow-%' OR request_id LIKE 'fail-%' OR request_id LIKE 'qcfail-%'
         ORDER BY create_time;"

echo ""
echo "========== 10) 停止应用 =========="
stop_app
echo "✅ 完成。完整日志：$LOG"

#!/bin/bash
# ============================================================
# 本地引擎端到端冒烟测试
#
# 用法：  bash scripts/smoke-test.sh
# 产物：  taskflow-center/target/smoke-test.log （target/ 已被 .gitignore 忽略）
#
# 验证内容：
#   1) 提交 VIP 任务（priority=10）→ 引擎日志应显示已入队且 priority=10
#   2) 提交 NORMAL 任务（priority=1）
#   3) 幂等：重复提交同一 requestId → 返回同一 taskId 且 duplicated=true
#   4) 全链路：入口桶 → 搬运工 → transcribeQueue → 转写 → 质检入口桶 → 搬运工 → qcQueue → 质检
#   5) DB 状态（第 2 组阶段应为 QUEUED；接入真实 handler 后应为终态）
# ============================================================
set -u

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CENTER_DIR="$PROJECT_DIR/taskflow-center"
JAR="$CENTER_DIR/target/taskflow-center-1.0.0-SNAPSHOT.jar"
LOG="$CENTER_DIR/target/smoke-test.log"

API=http://localhost:8081/api/task
REQ_VIP="smoke-vip-001"
REQ_NOR="smoke-nor-001"

MYSQL_BIN="/c/Program Files/MySQL/MySQL Server 8.0/bin"
DB_USER=root
DB_PASS=jinjian20010210
DB_NAME=taskflow

if [ ! -f "$JAR" ]; then
  echo "❌ 找不到 $JAR，请先执行："
  echo "   mvn -pl taskflow-engine-local,taskflow-center -am package -DskipTests"
  exit 1
fi

# ---------- 停掉占用 8081 的进程 ----------
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
if [ -x "$MYSQL_BIN/mysql" ]; then
  "$MYSQL_BIN/mysql" --default-character-set=utf8mb4 -u"$DB_USER" -p"$DB_PASS" -D "$DB_NAME" \
    -e "DELETE FROM task WHERE request_id LIKE 'smoke-%';" 2>/dev/null
fi

# ---------- 启动应用 ----------
echo "=== 启动应用 ==="
rm -f "$LOG"
cd "$CENTER_DIR"
java -jar "$JAR" > "$LOG" 2>&1 &
APP_PID=$!

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

# ---------- 接口调用 ----------
echo ""
echo "========== 1) 提交 VIP 任务 (priority=10) =========="
curl -s -X POST "$API/submit" -H "Content-Type: application/json" \
  -d "{\"requestId\":\"$REQ_VIP\",\"taskType\":\"MOCK_TASK\",\"priority\":10,\"params\":{\"msg\":\"vip\"}}"
echo ""

echo "========== 2) 提交 NORMAL 任务 (priority=1) =========="
curl -s -X POST "$API/submit" -H "Content-Type: application/json" \
  -d "{\"requestId\":\"$REQ_NOR\",\"taskType\":\"MOCK_TASK\",\"priority\":1,\"params\":{\"msg\":\"nor\"}}"
echo ""

echo "========== 3) 幂等：重复提交同一 requestId =========="
curl -s -X POST "$API/submit" -H "Content-Type: application/json" \
  -d "{\"requestId\":\"$REQ_VIP\",\"taskType\":\"MOCK_TASK\",\"priority\":10,\"params\":{\"msg\":\"vip\"}}"
echo ""

sleep 2

# ---------- 引擎日志 ----------
echo ""
echo "========== 引擎关键日志（按时间顺序）=========="
grep -E "任务已入队|入口桶已满|\[转写\]|\[质检\]|已死亡|停滞|ERROR" "$LOG" | tail -20

# ---------- DB 状态 ----------
echo ""
echo "========== DB 状态 =========="
if [ -x "$MYSQL_BIN/mysql" ]; then
  "$MYSQL_BIN/mysql" --default-character-set=utf8mb4 -u"$DB_USER" -p"$DB_PASS" -D "$DB_NAME" \
    -e "SELECT id, request_id, status, priority FROM task WHERE request_id LIKE 'smoke-%' ORDER BY create_time;" 2>/dev/null
else
  echo "(未找到 mysql 客户端，跳过 DB 校验)"
fi

# ---------- 收尾 ----------
echo ""
echo "========== 停止应用 =========="
stop_app
echo "✅ 完成。完整日志：$LOG"

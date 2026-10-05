#!/bin/bash
# ============================================================
# 可靠性测试：补偿扫描 + 启动恢复
#
# 用法：  bash scripts/reliability-test.sh
# 日志：  taskflow-center/target/reliability-test.log
#
# 【1】补偿扫描
#     直接在 DB 里 INSERT 一条 status=CREATED 且 update_time 为 5 分钟前的任务
#     （模拟"offer 失败 / CAS 后进程中断"），不做任何投递。
#     断言：靠补偿扫描把它捞起来并跑完 → SUCCESS
#     为了让测试快，启动时把阈值调小（stuck=1s, interval=2s）
#
# 【2】启动恢复
#     提交一个慢任务（转写 3s），1 秒后 taskkill /F 模拟崩溃，
#     此时任务卡在 QUEUED 或 TRANSCRIBING（内存队列已随进程消失）。
#     断言：重启后被启动恢复重建并重跑 → SUCCESS
# ============================================================
set -u

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CENTER_DIR="$PROJECT_DIR/taskflow-center"
JAR="$CENTER_DIR/target/taskflow-center-1.0.0-SNAPSHOT.jar"
LOG="$CENTER_DIR/target/reliability-test.log"

API=http://localhost:8081/api/task
MYSQL_BIN="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql"
DB_USER=root
DB_PASS=jinjian20010210
DB_NAME=taskflow

COMP_ID=8800000000000000001        # 直接 INSERT 的补偿测试任务 id（必须 < bigint 上限 9223372036854775807）
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

kill_app_hard() {   # 模拟崩溃（不给优雅关闭的机会）
  local pid
  pid=$(netstat -ano 2>/dev/null | grep ":8081" | grep -i listen | awk '{print $NF}' | head -1)
  if [ -n "$pid" ]; then
    taskkill //F //PID "$pid" >/dev/null 2>&1
    echo "💥 已强制杀掉进程 PID=$pid（模拟崩溃）"
  fi
  sleep 1
}

start_app() {   # $@ = 额外启动参数
  # ⚠️ Spring 的 "Started xxxApplication" 日志在 ApplicationRunner 之前打印，
  #    所以必须再等「启动恢复完成」，否则脚本的 INSERT 会与启动恢复撞车。
  local mark
  touch "$LOG"                      # 首次启动时日志还不存在，先建空文件，避免 wc 报错
  mark=$(wc -l < "$LOG")

  cd "$CENTER_DIR" || exit 1
  java -jar "$JAR" "$@" >> "$LOG" 2>&1 &

  local i
  for i in $(seq 1 40); do
    tail -n +$((mark + 1)) "$LOG" | grep -q "Started TaskFlowCenterApplication" && break
    sleep 1
  done
  tail -n +$((mark + 1)) "$LOG" | grep -q "Started TaskFlowCenterApplication" || return 1

  for i in $(seq 1 30); do
    tail -n +$((mark + 1)) "$LOG" | grep -qE "启动恢复完成" && return 0
    sleep 1
  done
  return 1
}

echo "=== 准备：清理数据与端口 ==="
stop_app
mysql_q "DELETE FROM task WHERE request_id LIKE 'comp-%' OR request_id LIKE 'slow-%';"
rm -f "$LOG"

# ============================================================
# 【1】补偿扫描
# ============================================================
echo ""
echo "############ 【1】补偿扫描 ############"
echo "启动应用（stuck=1s, compensate-interval=2s，便于快速验证）"
if ! start_app --taskflow.pipeline.created-stuck-seconds=1 \
               --taskflow.pipeline.transcribed-stuck-seconds=1 \
               --taskflow.pipeline.compensate-interval-millis=2000; then
  echo "❌ 启动失败"; tail -20 "$LOG"; stop_app; exit 1
fi
echo "✅ 应用已启动"

echo ""
echo "--- 1.1 直接 INSERT 一条卡住的 CREATED 任务（update_time = 5 分钟前）---"
# 这里不用 mysql_q：INSERT 失败时必须把报错显出来，不能让测试静默通过
"$MYSQL_BIN" --default-character-set=utf8mb4 -u"$DB_USER" -p"$DB_PASS" -D "$DB_NAME" -e \
  "INSERT INTO task (id, request_id, batch_id, task_type, params, status, priority,
                     retry_count, max_retry, create_time, update_time, deleted)
   VALUES ($COMP_ID, 'comp-stuck-001', NULL, 'MOCK_TASK', '{\"msg\":\"comp\"}',
           0, 1, 0, 3,
           DATE_SUB(NOW(), INTERVAL 5 MINUTE), DATE_SUB(NOW(), INTERVAL 5 MINUTE), 0);" 2>&1 \
  | grep -v "Using a password"

if [ "$(mysql_q "SELECT COUNT(*) FROM task WHERE id=$COMP_ID;")" != "1" ]; then
  echo "❌ INSERT 未生效，测试无法继续"
  stop_app
  exit 1
fi
echo "已插入 taskId=$COMP_ID, status=$(status_name "$(mysql_q "SELECT status FROM task WHERE id=$COMP_ID;")")"

echo ""
echo "--- 1.2 等待补偿扫描（最多 12s）---"
for i in $(seq 1 12); do
  CODE=$(mysql_q "SELECT status FROM task WHERE id=$COMP_ID;")
  printf "  t=%ss status=%s (%s)\n" "$i" "${CODE:-?}" "$(status_name "${CODE:-}")"
  [ "${CODE:-}" = "5" ] && break
  sleep 1
done

CODE=$(mysql_q "SELECT status FROM task WHERE id=$COMP_ID;")
if [ "${CODE:-}" = "5" ]; then
  echo "✅ 【1】补偿生效：卡住的任务被补偿扫描捞起并执行到 SUCCESS"
else
  echo "❌ 【1】补偿失败：最终状态 = ${CODE:-?} ($(status_name "${CODE:-}"))"
  FAIL=1
fi
echo "  补偿日志："
grep -E "补偿 (CREATED|TRANSCRIBED)" "$LOG" | tail -3 | sed 's/^/    /'

# ============================================================
# 【2】启动恢复
# ============================================================
echo ""
echo "############ 【2】启动恢复（模拟崩溃）############"

RESP=$(curl -s -X POST "$API/submit" -H "Content-Type: application/json" \
  -d '{"requestId":"slow-crash-001","taskType":"MOCK_TASK","priority":1,"params":{"msg":"crash"}}')
echo "提交慢任务: $RESP"
SLOW_ID=$(echo "$RESP" | sed -n 's/.*"taskId":"\([0-9]*\)".*/\1/p')

sleep 1
BEFORE=$(mysql_q "SELECT status FROM task WHERE id=$SLOW_ID;")
echo "崩溃前状态: ${BEFORE:-?} ($(status_name "${BEFORE:-}"))"

kill_app_hard

AFTER_KILL=$(mysql_q "SELECT status FROM task WHERE id=$SLOW_ID;")
echo "崩溃后 DB 状态（未重启）: ${AFTER_KILL:-?} ($(status_name "${AFTER_KILL:-}"))  ← 内存队列已消失，任务实际上卡住了"

echo ""
echo "--- 2.1 重启应用（同样的快阈值参数）---"
if ! start_app --taskflow.pipeline.created-stuck-seconds=1 \
               --taskflow.pipeline.transcribed-stuck-seconds=1 \
               --taskflow.pipeline.compensate-interval-millis=2000; then
  echo "❌ 重启失败"; tail -20 "$LOG"; stop_app; exit 1
fi
echo "✅ 重启完成"

echo ""
echo "--- 2.2 等待恢复执行（最多 20s）---"
for i in $(seq 1 20); do
  CODE=$(mysql_q "SELECT status FROM task WHERE id=$SLOW_ID;")
  printf "  t=%ss status=%s (%s)\n" "$i" "${CODE:-?}" "$(status_name "${CODE:-}")"
  [ "${CODE:-}" = "5" ] && break
  sleep 1
done

CODE=$(mysql_q "SELECT status FROM task WHERE id=$SLOW_ID;")
if [ "${CODE:-}" = "5" ]; then
  echo "✅ 【2】启动恢复生效：崩溃时卡住的任务被重建并执行到 SUCCESS"
else
  echo "❌ 【2】启动恢复失败：最终状态 = ${CODE:-?} ($(status_name "${CODE:-}"))"
  FAIL=1
fi

echo ""
echo "  启动恢复日志："
grep -E "启动恢复" "$LOG" | tail -3 | sed 's/^/    /'
echo "  该任务的状态流转轨迹（应能看到两次 QUEUED -> TRANSCRIBING）："
grep -F "taskId=$SLOW_ID" "$LOG" | grep -F "状态流转" | sed 's/^/    /'

echo ""
echo "========== 停止应用 =========="
stop_app
if [ "$FAIL" = "0" ]; then
  echo "✅ 全部通过。完整日志：$LOG"
else
  echo "❌ 存在失败项，请查看日志：$LOG"
  exit 1
fi

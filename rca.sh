#!/usr/bin/env bash
# 调用 RCA Agent 的 AG-UI 端点，把 SSE 事件流整理成可读输出。
# 只依赖 bash / curl / sed，不需要 jq 或 python。
#
#   ./rca.sh case01              分析案例（默认动作）
#   ./rca.sh -m "你的问题"        自由提问
#   ./rca.sh -t case01           只看工具调用序列，不打正文
#   ./rca.sh -r case01           原始 JSON，排查协议问题时用
#
# 环境变量可覆盖：RCA_HOST / RCA_PREFIX / RCA_USER_ID
set -uo pipefail

HOST="${RCA_HOST:-http://localhost:9001}"
PREFIX="${RCA_PREFIX:-/wjq4709698/api}"   # 要与 application.yml 的 agui.path-prefix 一致
USER_ID="${RCA_USER_ID:-local}"

MODE="pretty"
MESSAGE=""

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

while getopts ":m:trh" opt; do
    case "$opt" in
        m) MESSAGE="$OPTARG" ;;
        t) MODE="tools" ;;
        r) MODE="raw" ;;
        h) usage ;;
        \?) echo "未知参数: -$OPTARG" >&2; usage ;;
    esac
done
shift $((OPTIND - 1))

CASE_ID="${1:-}"

if [[ -z "$MESSAGE" ]]; then
    [[ -z "$CASE_ID" ]] && usage
    # 这句 prompt 有讲究：不点名 AGENTS.md 和 write_report，模型容易跳过落盘那一步
    MESSAGE="请分析故障案例 ${CASE_ID}，按 AGENTS.md 规定的流程走完，最后调用 write_report 落盘。"
fi

# JSON 字符串转义：反斜杠、双引号、换行
json_escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n' ' '
}

THREAD_ID="${CASE_ID:-chat}-$(date +%Y%m%d-%H%M%S)"
RUN_ID="run-$(date +%s)"

PAYLOAD=$(printf '{"threadId":"%s","runId":"%s","userId":"%s","messages":[{"role":"user","content":"%s"}]}' \
    "$(json_escape "$THREAD_ID")" \
    "$(json_escape "$RUN_ID")" \
    "$(json_escape "$USER_ID")" \
    "$(json_escape "$MESSAGE")")

echo "---------------------------------------------"
echo "  POST ${HOST}${PREFIX}/ag-ui"
echo "  thread: ${THREAD_ID}"
echo "  prompt: ${MESSAGE}"
echo "---------------------------------------------"

START=$(date +%s)
TOOLS_LOG=$(mktemp)
trap 'rm -f "$TOOLS_LOG"' EXIT

CYAN=$'\033[36m'; GRAY=$'\033[90m'; RED=$'\033[31m'; YEL=$'\033[33m'; OFF=$'\033[0m'

# 取一个字符串字段的值（字段值里不含转义引号时可靠，工具名/类型/状态都满足）
field() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\\([^\"]*\\)\".*/\\1/p" | head -1; }

# delta 在 payload 里是最后一个字段，单独按行尾匹配，并还原常见转义
delta_of() {
    printf '%s' "$1" \
        | sed -n 's/.*"delta":"\(.*\)"}$/\1/p' \
        | sed -e 's/\\n/\
/g' -e 's/\\t/\t/g' -e 's/\\"/"/g' -e 's/\\\\/\\/g'
}

curl -sS -N --max-time 1800 -X POST "${HOST}${PREFIX}/ag-ui" \
    -H 'Content-Type: application/json' \
    -H 'Accept: text/event-stream' \
    -d "$PAYLOAD" \
| while IFS= read -r line; do
    case "$line" in
        data:*) ;;
        *) continue ;;
    esac
    json="${line#data:}"
    [[ -z "${json// }" ]] && continue

    if [[ "$MODE" == "raw" ]]; then
        printf '%s\n' "$json"
        continue
    fi

    type=$(field "$json" type)

    case "$type" in
        TOOL_CALL_START)
            name=$(field "$json" toolCallName)
            echo "$name" >> "$TOOLS_LOG"
            n=$(wc -l < "$TOOLS_LOG" | tr -d ' ')
            printf '\n%s[%s] %s%s\n' "$CYAN" "$n" "${name:-?}" "$OFF"
            ;;
        TOOL_CALL_RESULT)
            [[ "$(field "$json" status)" == "completed" ]] || continue
            printf '%s    -> %s %s%s\n' "$GRAY" "$(field "$json" toolCallName)" "$(field "$json" state)" "$OFF"
            ;;
        TEXT_MESSAGE_CONTENT)
            [[ "$MODE" == "tools" ]] && continue
            printf '%s' "$(delta_of "$json")"
            ;;
        RUN_ERROR)
            printf '\n%s出错 [%s] %s%s\n' "$RED" "$(field "$json" code)" "$(field "$json" message)" "$OFF"
            ;;
    esac
done

# 流程自检
echo
echo
echo "工具调用序列 ($(wc -l < "$TOOLS_LOG" | tr -d ' ') 次):"
nl -ba -w3 -s'. ' "$TOOLS_LOG" 2>/dev/null

MISSING=""
for t in load_skill_through_path analyze_metric_tables parse_exception write_report; do
    grep -qx "$t" "$TOOLS_LOG" 2>/dev/null || MISSING="$MISSING $t"
done
if [[ -n "$MISSING" ]]; then
    printf '\n%s未调用:%s%s\n' "$YEL" "$MISSING" "$OFF"
    case "$MISSING" in
        *load_skill_through_path*)
            printf '%s  一次都没加载 skill，说明 AGENTS.md 的流程约束没生效%s\n' "$YEL" "$OFF" ;;
    esac
    case "$MISSING" in
        *write_report*) printf '%s  报告没落盘%s\n' "$YEL" "$OFF" ;;
    esac
fi

echo
echo "---------------------------------------------"
echo "  耗时 $(( $(date +%s) - START ))s"
ls -t workspace/reports/*.md 2>/dev/null | head -1 | sed 's/^/  最新报告: /'
echo "---------------------------------------------"
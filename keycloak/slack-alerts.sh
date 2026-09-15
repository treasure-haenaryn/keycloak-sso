#!/usr/bin/env bash
# Polls Keycloak's user/admin event log and forwards alert-worthy events to Slack.
#
# Never advances past an event it failed to deliver: a Slack outage delays
# notifications (retried on the next run) rather than losing them, since
# Keycloak's own event_entity/admin_event_entity tables stay the durable
# source of truth regardless of whether this script's delivery ever succeeds.
#
# Usage (intended to run on a schedule, e.g. cron every 1-5 minutes):
#   SLACK_WEBHOOK_URL=https://hooks.slack.com/services/... ./slack-alerts.sh
#
# First run only establishes a baseline (current time) and sends nothing -
# it does not replay whatever history already accumulated before this was
# set up.

set -uo pipefail

KC_URL="${KC_URL:-http://idp.local:8080}"
REALM="${REALM:-sso-poc}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin}"
RESOLVE="${RESOLVE:-idp.local:8080:127.0.0.1}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_FILE="${STATE_FILE:-$SCRIPT_DIR/.slack-alert-state.json}"
SLACK_WEBHOOK_URL="${SLACK_WEBHOOK_URL:?SLACK_WEBHOOK_URL 환경변수(Slack Incoming Webhook URL)를 설정해야 합니다}"

# Only these user event types are alert-worthy; admin events (group/role/client
# changes etc.) are all forwarded since there aren't many and most matter.
ALERT_USER_EVENT_TYPES="LOGIN_ERROR,USER_DISABLED_BY_PERMANENT_LOCKOUT,USER_DISABLED_BY_TEMPORARY_LOCKOUT"

NOW_MS=$(( $(date +%s) * 1000 ))

if [[ ! -f "$STATE_FILE" ]]; then
  python3 -c "import json; json.dump({'user_watermark': $NOW_MS, 'admin_watermark': $NOW_MS}, open('$STATE_FILE', 'w'))"
  echo "첫 실행: 워터마크를 현재 시각으로 초기화했습니다. 기존에 쌓여있던 이벤트는 알리지 않습니다."
  exit 0
fi

USER_WM=$(python3 -c "import json; print(json.load(open('$STATE_FILE'))['user_watermark'])")
ADMIN_WM=$(python3 -c "import json; print(json.load(open('$STATE_FILE'))['admin_watermark'])")

# Keycloak's dateFrom filter is day-granularity, so ask for a day of margin
# before the older watermark and do the precise time>watermark filtering
# ourselves below.
OLDER_WM=$(( USER_WM < ADMIN_WM ? USER_WM : ADMIN_WM ))
DATE_FROM=$(python3 -c "
import datetime
print((datetime.datetime.fromtimestamp($OLDER_WM/1000, datetime.timezone.utc) - datetime.timedelta(days=1)).strftime('%Y-%m-%d'))
")

TOKEN=$(curl -s --resolve "$RESOLVE" -X POST "$KC_URL/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" -d "username=$ADMIN_USER" -d "password=$ADMIN_PASS" -d "grant_type=password" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

USER_EVENTS_JSON=$(curl -s --resolve "$RESOLVE" -H "Authorization: Bearer $TOKEN" \
  "$KC_URL/admin/realms/$REALM/events?dateFrom=$DATE_FROM&max=1000")
ADMIN_EVENTS_JSON=$(curl -s --resolve "$RESOLVE" -H "Authorization: Bearer $TOKEN" \
  "$KC_URL/admin/realms/$REALM/admin-events?dateFrom=$DATE_FROM&max=1000")

# Build a single time-ascending, newline-delimited-JSON queue of
# {source, time, message} - merging both streams so alerts arrive in the
# order things actually happened.
# (Passed via env vars rather than interpolated into the Python source text -
# JSON containing quotes/backslashes would otherwise break the string literal.)
QUEUE=$(USER_EVENTS_JSON="$USER_EVENTS_JSON" ADMIN_EVENTS_JSON="$ADMIN_EVENTS_JSON" python3 -c "
import json, os

user_events = json.loads(os.environ['USER_EVENTS_JSON'])
admin_events = json.loads(os.environ['ADMIN_EVENTS_JSON'])
alert_types = set('$ALERT_USER_EVENT_TYPES'.split(','))
user_wm = $USER_WM
admin_wm = $ADMIN_WM

items = []
for e in user_events:
    if e['time'] <= user_wm or e['type'] not in alert_types:
        continue
    who = e.get('details', {}).get('username', e.get('userId', '?'))
    err = f\" ({e['error']})\" if e.get('error') else ''
    msg = f\":rotating_light: [{e['type']}] {who} @ {e.get('clientId','?')} from {e.get('ipAddress','?')}{err}\"
    items.append({'source': 'user', 'time': e['time'], 'message': msg})

for e in admin_events:
    if e['time'] <= admin_wm:
        continue
    admin_user = e.get('authDetails', {}).get('userId', '?')
    msg = f\":wrench: [ADMIN {e.get('operationType','?')}] {e.get('resourceType','?')} {e.get('resourcePath','')} by {admin_user}\"
    items.append({'source': 'admin', 'time': e['time'], 'message': msg})

items.sort(key=lambda x: x['time'])
for it in items:
    print(json.dumps(it))
")

if [[ -z "$QUEUE" ]]; then
  echo "새로운 알림 대상 이벤트 없음."
  exit 0
fi

NEW_USER_WM=$USER_WM
NEW_ADMIN_WM=$ADMIN_WM
SENT=0
FAILED=0

while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  SOURCE=$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['source'])" "$line")
  TIME=$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['time'])" "$line")
  MESSAGE=$(python3 -c "import json,sys; print(json.loads(sys.argv[1])['message'])" "$line")

  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H 'Content-Type: application/json' \
    -d "$(python3 -c "import json,sys; print(json.dumps({'text': sys.argv[1]}))" "[sso-poc] $MESSAGE")" \
    "$SLACK_WEBHOOK_URL")

  if [[ "$HTTP_CODE" == "200" ]]; then
    SENT=$((SENT + 1))
    if [[ "$SOURCE" == "user" ]]; then NEW_USER_WM=$TIME; else NEW_ADMIN_WM=$TIME; fi
  else
    echo "Slack 전송 실패(http_code=$HTTP_CODE), 이 지점부터는 다음 실행에서 재시도합니다: $MESSAGE" >&2
    FAILED=1
    break
  fi
done <<< "$QUEUE"

python3 -c "import json; json.dump({'user_watermark': $NEW_USER_WM, 'admin_watermark': $NEW_ADMIN_WM}, open('$STATE_FILE', 'w'))"

echo "전송 완료: ${SENT}건. 실패로 중단됨: $([[ $FAILED -eq 1 ]] && echo yes || echo no)"

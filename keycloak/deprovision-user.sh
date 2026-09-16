#!/usr/bin/env bash
# Disables a user AND revokes their existing Keycloak sessions in one step.
#
# Disabling a user alone (enabled=false) does NOT invalidate sessions/tokens
# already issued - a browser that logged in before the disable keeps working
# until its own app-side session naturally expires (oauth2-proxy's cookie_expire,
# or a directly-integrated app's own session timeout), since neither re-checks
# the account's enabled status on every request. Revoking the Keycloak session
# here forces the next refresh_token exchange to fail, which is what actually
# cuts the user off - bounded by the access token lifespan (90s in this realm),
# not by whatever the app's own session cookie duration happens to be.
#
# Usage: ./deprovision-user.sh <username>

set -euo pipefail

KC_URL="${KC_URL:-http://idp.local:8080}"
REALM="${REALM:-sso-poc}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin}"
RESOLVE="${RESOLVE:-idp.local:8080:127.0.0.1}"

USERNAME="${1:?사용할 방법: ./deprovision-user.sh <username>}"

TOKEN=$(curl -s --resolve "$RESOLVE" -X POST "$KC_URL/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" -d "username=$ADMIN_USER" -d "password=$ADMIN_PASS" -d "grant_type=password" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

USER_ID=$(curl -s --resolve "$RESOLVE" -H "Authorization: Bearer $TOKEN" \
  "$KC_URL/admin/realms/$REALM/users?username=$USERNAME&exact=true" \
  | python3 -c "
import sys, json
users = json.load(sys.stdin)
if not users:
    print('')
else:
    print(users[0]['id'])
")

if [[ -z "$USER_ID" ]]; then
  echo "사용자를 찾을 수 없습니다: $USERNAME" >&2
  exit 1
fi

echo "1/2 계정 비활성화 중... ($USERNAME / $USER_ID)"
curl -s --resolve "$RESOLVE" -o /dev/null -w "  http_status=%{http_code}\n" -X PUT \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"enabled": false}' \
  "$KC_URL/admin/realms/$REALM/users/$USER_ID"

echo "2/2 기존 세션 강제 종료 중..."
curl -s --resolve "$RESOLVE" -o /dev/null -w "  http_status=%{http_code}\n" -X POST \
  -H "Authorization: Bearer $TOKEN" \
  "$KC_URL/admin/realms/$REALM/users/$USER_ID/logout"

echo "완료: $USERNAME 계정이 비활성화되고 기존 세션이 종료되었습니다."
echo "(이미 로그인해 있던 앱들은 다음 access token 갱신 시점부터 차단됩니다 - 이 realm은 access.token.lifespan=90초)"

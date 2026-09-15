#!/usr/bin/env bash
# Demonstrates real Keycloak signing-key rotation via the Admin REST API.
#
# There's no built-in timer for this - rotation is an admin action: add a new
# rsa-generated key provider with a higher priority (new tokens sign with it,
# the old key stays in JWKS so already-issued tokens keep verifying), then
# later remove the old provider once its tokens have all expired.
#
# Usage:
#   ./rotate-keys.sh              # list current signing key providers
#   ./rotate-keys.sh --add        # add a new provider with higher priority
#   ./rotate-keys.sh --remove ID  # remove a provider by its component id

set -euo pipefail

KC_URL="${KC_URL:-http://idp.local:8080}"
REALM="${REALM:-sso-poc}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin}"
RESOLVE="${RESOLVE:-idp.local:8080:127.0.0.1}"

get_admin_token() {
  curl -s --resolve "$RESOLVE" -X POST "$KC_URL/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli" -d "username=$ADMIN_USER" -d "password=$ADMIN_PASS" -d "grant_type=password" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])"
}

get_realm_internal_id() {
  curl -s --resolve "$RESOLVE" -H "Authorization: Bearer $1" \
    "$KC_URL/admin/realms/$REALM" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])"
}

list_providers() {
  curl -s --resolve "$RESOLVE" -H "Authorization: Bearer $1" \
    "$KC_URL/admin/realms/$REALM/components?type=org.keycloak.keys.KeyProvider" \
    | python3 -c "
import sys, json
for c in json.load(sys.stdin):
    if c['providerId'] == 'rsa-generated':
        print(f\"id={c['id']}  name={c['name']}  priority={c['config']['priority'][0]}\")
"
}

max_priority() {
  curl -s --resolve "$RESOLVE" -H "Authorization: Bearer $1" \
    "$KC_URL/admin/realms/$REALM/components?type=org.keycloak.keys.KeyProvider" \
    | python3 -c "
import sys, json
prios = [int(c['config']['priority'][0]) for c in json.load(sys.stdin) if c['providerId'] == 'rsa-generated']
print(max(prios) if prios else 0)
"
}

cmd="${1:-list}"
TOKEN=$(get_admin_token)

case "$cmd" in
  --add|add)
    REALM_ID=$(get_realm_internal_id "$TOKEN")
    NEW_PRIORITY=$(( $(max_priority "$TOKEN") + 100 ))

    echo "추가 전 서명키 목록:"
    list_providers "$TOKEN"
    echo
    echo "priority=$NEW_PRIORITY 로 새 서명키 추가 중..."
    curl -s --resolve "$RESOLVE" -o /dev/null -w "생성 http_code: %{http_code}\n" \
      -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      "$KC_URL/admin/realms/$REALM/components" \
      -d "{\"name\":\"rsa-generated-$(date +%s)\",\"providerId\":\"rsa-generated\",\"providerType\":\"org.keycloak.keys.KeyProvider\",\"parentId\":\"$REALM_ID\",\"config\":{\"priority\":[\"$NEW_PRIORITY\"]}}"

    echo
    echo "추가 후 서명키 목록 (앞으로 발급되는 토큰은 여기서 priority가 가장 높은 키로 서명됨):"
    list_providers "$TOKEN"
    ;;

  --remove|remove)
    COMPONENT_ID="${2:?사용법: rotate-keys.sh --remove <component-id>}"

    echo "삭제 전 서명키 목록:"
    list_providers "$TOKEN"
    echo
    curl -s --resolve "$RESOLVE" -o /dev/null -w "삭제 http_code: %{http_code}\n" \
      -X DELETE -H "Authorization: Bearer $TOKEN" \
      "$KC_URL/admin/realms/$REALM/components/$COMPONENT_ID"

    echo
    echo "삭제 후 서명키 목록 (이 키로 서명됐던 토큰은 이제 검증 실패함):"
    list_providers "$TOKEN"
    ;;

  list|*)
    echo "현재 서명키(rsa-generated) Provider 목록:"
    list_providers "$TOKEN"
    ;;
esac

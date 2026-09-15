# keycloak-sso

3개의 가짜 CMS(member-cms, payment-cms, modern-cms)를 Keycloak으로 SSO 연동하는 토이 프로젝트.

- `member-cms`, `payment-cms`: 코드 수정이 어려운 레거시 앱을 가정. 자체 로그인 로직 없이, 앞단의 `oauth2-proxy`가 Keycloak과 OIDC를 수행하고 신뢰 헤더(`X-Forwarded-Email`)로 신원을 넘겨받아 세션을 생성
- `modern-cms`: 코드를 직접 고칠 수 있는 앱을 가정. Spring Security `oauth2Login`으로 Keycloak과 직접 OIDC 연동
- 인가는 프록시 레벨에서 Keycloak Group 멤버십으로 처리 (앱까지 요청을 보내지 않고 프록시가 차단)
- 로그인 아이디는 앱마다 다를 수 있어서, Keycloak 사용자 커스텀 속성(`member_cms_username` 등)을 Protocol Mapper로 토큰 클레임에 실어 앱별로 다른 아이디로 로그인시킴

## 사전 준비

1. Docker Desktop 실행 중이어야 함
2. `/etc/hosts`에 아래 한 줄 추가 (sudo 권한 필요):
   ```
   127.0.0.1 idp.local
   ```
   Keycloak의 `KC_HOSTNAME`이 `idp.local`로 고정되어 있어서, 브라우저와 컨테이너 양쪽이 같은 호스트명으로 접근해야 토큰 발급/검증이 일관되게 동작함 (컨테이너 쪽은 docker-compose의 네트워크 alias로 이미 해결됨).

## 실행

```bash
docker compose up --build
```

첫 실행은 Maven 빌드가 포함돼서 몇 분 걸릴 수 있음. 모든 컨테이너가 뜨면:

- 키클록 Admin Console: http://idp.local:8080 (admin / admin)
- member-cms (via proxy): http://localhost:4181
- payment-cms (via proxy): http://localhost:4182
- modern-cms (직접 OIDC): http://localhost:8083

테스트 계정: `lee` / `lee1227` (모든 CMS 접근 가능), `seo` / `seo1127` (member-cms만 접근 가능)

## ⚠️ 최우선 검증 항목

`oauth2-proxy`의 `allowed_groups` 옵션이 `provider=keycloak-oidc`에서 Keycloak의 Group Membership 클레임과 정확히 매칭되는지는 실제로 띄워보기 전까지 확정할 수 없음. **아래 시나리오 2번(권한 없는 사용자 확인)을 가장 먼저, 가장 꼼꼼히 확인할 것** — 특히 seo가 payment-cms에 막히지 않고 통과되어 버리는 "fail open" 상황이 없는지 반드시 확인.
매칭이 안 되면 `oauth2-proxy` 로그에서 어떤 클레임/형식을 기대하는지 확인 후 `oidc_groups_claim` 지정이나 Group Membership 매퍼의 `full.path` 토글을 조정.

## 테스트 시나리오

### 1. SSO 확인
1. http://localhost:4181 접속 → 키클록 로그인 화면에서 lee로 로그인
2. member-cms 화면에 "lee.dev"로 로그인됐다고 뜨는지 확인
3. 이어서 http://localhost:4182 접속 → 로그인 화면 없이 바로 payment-cms에 "leedev"로 들어가는지 확인 (SSO)

### 2. 권한 없는 사용자 확인 (가장 중요, 위 경고 참고)
1. 로그아웃 후 seo로 로그인
2. member-cms(4181)는 정상 진입 ("seo.dev")
3. payment-cms(4182)는 oauth2-proxy가 Group 확인 단계에서 막아서 403이 뜨는지 확인
4. `docker compose logs payment-cms`로 실제로 요청이 앱까지 안 들어갔는지 확인 (앱 로그에 아무 흔적도 없어야 함)

### 3. 네트워크 격리 확인
```bash
# 호스트에서 member-cms 포트로 직접 접근 시도 - 실패해야 함 (포트 자체가 공개 안 됨)
curl http://localhost:8080  # member-cms의 내부 포트, 이 번호로는 애초에 호스트에 안 열려있음

# 컨테이너 내부에서는 접근 가능함을 대조 확인
docker compose exec oauth2-proxy-member wget -qO- http://member-cms:8080/whoami || true
```

### 4. 헤더 위조 방어 확인
```bash
curl -i -H "X-Forwarded-Email: admin" http://localhost:4181/
```
oauth2-proxy가 이 값을 무시하고 미인증 상태로 키클록 로그인 페이지로 리다이렉트(302)하는지 확인.

### 5. step-up MFA 확인 (수동 설정 필요)
키클록 기본 OTP를 카카오톡/SMS 인증의 자리표시자로 사용. Admin Console에서:
1. **Authentication → Flows** → 기존 `browser` flow를 복제해서 `browser-otp-required`로 이름 변경
2. 복제된 flow 안의 "OTP Form" 단계를 REQUIRED로 변경 (member/payment-cms의 flow는 건드리지 않음)
3. **Clients → modern-cms → Advanced → Authentication flow overrides → Browser Flow**를 `browser-otp-required`로 지정
4. modern-cms(8083) 로그인 시 OTP 등록/입력 화면이 강제되는지, member/payment-cms는 그대로 OTP 없이 되는지 비교

### 6. 통합 로그아웃(SLO) 확인
1. lee로 로그인해서 member-cms, payment-cms, modern-cms 세 곳 다 접속해둔 상태 만들기
2. member-cms에서 로그아웃 클릭
3. **member-cms**: 즉시 반영, 새로고침하면 바로 키클록 로그인 화면
4. **payment-cms**: 즉시는 아님 — Access Token Lifespan(90초) 이내에 자연스럽게 로그아웃 상태로 전환되는지 확인 (그 전엔 계속 접속될 수 있음, 정상 동작)
5. **modern-cms**: 진짜 back-channel logout이 동작하면 더 빠르게(수 초 내) 반영되는지 비교

### 7. 세션 만료 정책 확인
1. member-cms를 5분 넘게 계속 눌러가며 사용 → Access Token(90초)이 반복 갱신되는 동안 재로그인 요구 없이 계속 쓸 수 있는지 확인
2. payment-cms를 접속만 해두고 15분간 방치 → 재로그인 요구되는지 확인
3. member-cms는 같은 조건(15분 방치)에서 아직 안 끊기는지 비교 (realm 기본 30분)

### 8. Brute-force 보호 확인
lee 계정으로 틀린 비밀번호를 5회 연속 입력 → 그 다음부터 (정확한 비밀번호를 넣어도) 잠기는지, 시간이 지나면 자동으로 풀리는지 확인

### 9. 서명키 로테이션 확인
`keycloak/rotate-keys.sh`로 실제 키 로테이션 생명주기를 확인. Keycloak은 로테이션을 자동으로 하지 않고, 우선순위가 더 높은 새 키 Provider를 추가하는 관리자 액션으로 처리됨.

```bash
bash keycloak/rotate-keys.sh              # 현재 서명키 Provider 목록 확인
bash keycloak/rotate-keys.sh --add        # 우선순위가 더 높은 새 키 추가 (이제부터 새 토큰은 이 키로 서명)
bash keycloak/rotate-keys.sh --remove ID  # 지정한 옛 Provider 제거
```

1. 로그인해서 Access Token을 하나 받아두고(옛 키로 서명됨), `/realms/sso-poc/protocol/openid-connect/userinfo`에 그 토큰으로 호출 → 200 확인
2. `--add` 실행 → `/realms/sso-poc/protocol/openid-connect/certs`(JWKS)에 옛 kid와 새 kid가 둘 다 있는지 확인
3. 다시 로그인해서 새로 받은 토큰의 kid가 새 키와 일치하는지 확인 (새 로그인은 새 키로 서명됨)
4. 1번에서 받아둔 옛 토큰으로 다시 userinfo 호출 → 여전히 200 (옛 키가 아직 JWKS에 남아있어서 유효)
5. `--remove`로 옛 Provider 제거
6. 같은 옛 토큰으로 다시 userinfo 호출 → 이번엔 401 (옛 키가 사라져서 검증 불가)

### 10. Slack 알림 확인

`keycloak/slack-alerts.sh`가 키클록의 이벤트 로그(브루트포스, 계정 잠금, 관리자 작업)를 주기적으로 조회해서 Slack으로 보냄. Slack 전송이 실패해도 "마지막으로 확인한 시각"이 아니라 "마지막으로 성공적으로 보낸 지점"까지만 진행 상태를 저장해서, 실패한 이벤트는 다음 실행 때 다시 시도됨(유실 대신 지연).

**사전 준비**: Slack App에서 Incoming Webhooks 활성화 → 채널에 연결 → Webhook URL 확보 ([api.slack.com/apps](https://api.slack.com/apps) → 앱 선택 → Incoming Webhooks → Activate → Add New Webhook to Workspace)

```bash
# 최초 실행: 워터마크만 초기화하고 아무것도 안 보냄 (기존에 쌓인 이벤트 재알림 방지)
SLACK_WEBHOOK_URL="https://hooks.slack.com/services/..." bash keycloak/slack-alerts.sh

# 이후 실행: 그 시점 이후 발생한 이벤트만 전송
SLACK_WEBHOOK_URL="https://hooks.slack.com/services/..." bash keycloak/slack-alerts.sh
```

실제 운영 시에는 크론으로 주기 실행 (예: 1분마다):
```
* * * * * cd /path/to/keycloak-sso/keycloak && SLACK_WEBHOOK_URL="..." ./slack-alerts.sh >> /var/log/kc-slack-alerts.log 2>&1
```

**테스트 시나리오**:
1. seo 계정으로 틀린 비밀번호 5회 입력 (LOGIN_ERROR + 잠금 이벤트 발생) → 스크립트 실행 → Slack 채널에 도착 확인
2. 일부러 잘못된 `SLACK_WEBHOOK_URL`로 실행 → 실패 로그 출력, `.slack-alert-state.json`의 워터마크가 전진 안 하는지 확인
3. 정상 URL로 재실행 → 2번에서 놓쳤던 이벤트가 이번엔 도착하는지 확인 (유실 없이 재시도됨)
4. 그 사이 새로 로그인한 세션들(member-cms, payment-cms SSO 포함)은 계속 정상 동작하는지 확인

### 11. 전화/카카오 MFA (커스텀 Authenticator SPI) 확인

`modern-cms`(8083)만 `keycloak/extensions/phone-mfa-authenticator/`의 커스텀 MFA를 거치도록 바인딩되어 있음. member-cms/payment-cms는 영향 없음.

1. lee로 modern-cms(8083) 로그인 → 비밀번호 다음 "문자로 받은 인증 코드를 입력하세요" 화면이 뜨는지 확인
2. `docker compose logs keycloak | grep PHONE-MFA`로 발송된 코드를 확인 → 그 코드를 입력하면 로그인 성공
3. 틀린 코드를 5회 연속 입력 → "시도 횟수를 초과했습니다" 에러로 막히는지 확인
4. 코드 유효시간(기본 180초)이 지난 뒤 입력 → "코드가 만료되었습니다" 에러 확인 → 재발송 버튼으로 새 코드 받아 정상 로그인되는지 확인
5. member-cms(4181), payment-cms(4182)는 여전히 코드 입력 없이 그대로 로그인되는지 확인 (영향 없음)


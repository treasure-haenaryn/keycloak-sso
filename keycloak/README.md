# realm-export.json 구조 설명

JSON은 주석을 지원하지 않아서, `realm-export.json`의 각 부분이 어떤 기능을 담당하는지 이 문서에 별도로 정리함.

## 1. Realm 기본 / 세션 정책

```json
"realm": "sso-poc",
"ssoSessionIdleTimeout": 1800,      // 30분: 비활동 시 SSO 세션 만료
"ssoSessionMaxLifespan": 32400      // 9시간: 활동 여부와 무관한 절대 상한
```

- `ssoSessionIdleTimeout`/`ssoSessionMaxLifespan`: realm 전체의 기본 세션 정책. 개별 클라이언트는 `client.session.idle.timeout` 속성으로 이 값을 오버라이드할 수 있음 (payment-cms-proxy가 15분으로 더 타이트하게 사용).

## 2. Brute-force 보호

```json
"bruteForceProtected": true,
"failureFactor": 5,              // 5회 실패하면 잠금 시작
"waitIncrementSeconds": 60,       // 실패마다 대기시간 1분씩 증가
"maxFailureWaitSeconds": 900,     // 최대 대기시간 15분
"permanentLockout": false         // 영구 잠금 아님, 시간 지나면 자동 해제
```

로그인 시도 제한. 키클록 기본값은 꺼져있어서 명시적으로 켬.

## 3. Roles / Groups

```json
"roles": { "realm": [ { "name": "cms-user" } ] },
"groups": [
  { "name": "member-cms-users", "path": "/member-cms-users" },
  { "name": "payment-cms-users", "path": "/payment-cms-users" }
]
```

- `groups`: **인가(authorization)용**. "이 사람이 이 CMS에 접근 가능한가"를 판단하는 근거. oauth2-proxy의 `allowed_groups` 설정이 여기 있는 group path와 매칭됨.

## 4. Users

```json
"attributes": {
  "member_cms_username": ["lee.dev"],
  "payment_cms_username": ["leedev"]
}
```

- `attributes`: **신원 매핑용**. 같은 사람이 앱마다 다른 아이디를 쓸 수 있다는 걸 표현. Group과 역할이 다름 — Group은 "들어갈 수 있는지", 속성은 "들어가면 뭐라는 아이디인지".
- `seo`의 `payment_cms_username`이 `"__no_access__"`인 이유: 이 값을 비워두면(속성 자체를 생략하거나 빈 문자열로 두면) oauth2-proxy가 세션 생성 단계에서 에러를 내면서 죽어버림. 접근 권한이 없는 앱의 매핑 속성은 항상 non-empty placeholder로 채워야 함.

## 5. Clients

세 클라이언트 모두 `protocolMappers`가 각자의 `protocolMappers` 배열에 직접 정의되어 있음(dedicated scope) — realm 공유 Client Scope로 만들면 다른 클라이언트 토큰에도 값이 새어 들어가서 최소 권한 원칙에 어긋남.

**member-cms-proxy / payment-cms-proxy** (oauth2-proxy가 사용하는 클라이언트)
- `attributes."access.token.lifespan"`: 90초로 짧게. Access Token이 자주 만료돼야 로그아웃이 다른 앱으로 전파되는 속도가 빨라짐(refresh 시도 시점에 거절당하는 방식).
- `attributes."client.session.idle.timeout"` (payment만): 15분. 결제 관련 시스템이라 realm 기본(30분)보다 더 타이트하게.
- `protocolMappers` 중 `oidc-usermodel-attribute-mapper`: 유저 속성(`member_cms_username` 등)을 토큰 클레임으로 노출.
- `protocolMappers` 중 `oidc-group-membership-mapper`: Group 소속 정보를 `groups` 클레임으로 노출 (`full.path: true`라서 `/member-cms-users` 형태로 나옴, oauth2-proxy의 `allowed_groups` 값과 정확히 일치해야 함).

**modern-cms** (Spring Security가 직접 OIDC로 붙는 클라이언트)
- `attributes."backchannel.logout.*"`: 로그아웃 시 키클록이 이 클라이언트로 직접 서버간 신호를 보내는 back-channel logout 설정.
- `authenticationFlowBindingOverrides.browser`: 이 클라이언트로 로그인할 때만 기본 `browser` flow 대신 아래 6번의 `browser-otp-required` flow를 쓰게 하는 설정. member/payment-cms-proxy에는 이 오버라이드가 없어서 기본 flow(OTP 없음) 그대로 사용.

## 6. authenticationFlows — step-up MFA

```
browser-otp-required (top-level)
  ├─ Cookie / Kerberos / Identity Provider Redirector  (키클록 기본 browser flow와 동일)
  └─ browser-otp-required forms (subflow)
       ├─ Username Password Form (REQUIRED)
       └─ OTP Form (REQUIRED)
```

기본 `browser` flow를 복제해서 만든 커스텀 flow. 기본 flow에는 OTP가 "이미 등록한 사용자에게만" 조건부로 뜨는 서브플로우가 있는데, 여기서는 그 조건 없이 **OTP Form을 무조건 거치게** 만들어서, OTP를 처음 쓰는 사용자에게도 강제로 등록시킴 (카카오톡/SMS 인증의 자리표시자).

**주의**: `OTP Form`은 `browser-otp-required forms` 서브플로우 **안에**, `Username Password Form` **다음 순서**로 있어야 함. 최상위 레벨에 붙거나 순서가 바뀌면 `auth-otp-form requires user to be set` 에러가 나면서 로그인이 깨짐.

## 7. 실제 DB 테이블 구조 (Postgres)

`realm-export.json`의 각 항목이 실제로는 Postgres의 어느 테이블에 어떻게 나뉘어 저장되는지 정리. (`docker compose exec keycloak-db psql -U keycloak -d keycloak`로 직접 조회 가능, 또는 5432 포트로 로컬 GUI 툴 연결)

| 정보 | 테이블 | 핵심 컬럼 |
|---|---|---|
| Realm | `realm` | `id`, `name` |
| Group | `keycloak_group` | `id`, `realm_id`, `name` |
| 키클록 계정 | `user_entity` | `id`, `username`, `email` |
| CMS 계정 매칭(커스텀 속성) | `user_attribute` | `user_id`, `name`, `value` |
| 계정 ↔ Group 연결 | `user_group_membership` | `user_id`, `group_id` |
| 비밀번호 / OTP | `credential` | `user_id`, `type`, `credential_data`, `secret_data` |
| 스키마 자체 관리 이력 | `databasechangelog` | Liquibase가 자동 기록 (아래 참고) |

**전부 `user_id`/`realm_id`/`group_id` 외래키로 연결된 정규화된 구조**이지, 한 테이블에 계정 정보가 다 몰려있는 게 아님.

### 스키마(테이블 구조)는 키클록이 자동 생성함

이 테이블들은 저희가 설계한 게 아니라, 키클록이 빈 Postgres에 처음 연결될 때 내장된 **Liquibase** 마이그레이션 스크립트로 자동 생성한 것 (`databasechangelog` 테이블이 그 실행 이력). 로그에서도 확인됨:
```
Initializing database schema. Using changelog META-INF/jpa-changelog-master.xml
```
저희가 한 일은 "빈 Postgres를 연결해준 것"뿐이고, 테이블 구조 자체는 100% 키클록 몫.

### 테이블은 범용이고, 의미는 저희가 부여한 것

`user_attribute`는 `name`/`value`만 있는 완전 범용 key-value 저장소. `name`에 `"member_cms_username"`이라는 문자열을 넣은 것도, `keycloak_group`에 `"member-cms-users"`라는 이름을 지은 것도 전부 **저희가 설계한 의미**이지, 키클록이 "CMS 계정 매핑"이라는 개념을 원래부터 알고 있는 게 아님.

### 함정: Admin REST API로 커스텀 속성을 넣으려면 User Profile에 먼저 등록해야 함

`realm-export.json`의 **전체 import**는 이 검증을 생략하고 데이터를 그대로 넣지만, **Admin REST API로 사용자를 생성/수정할 때는 realm의 선언적 User Profile 스키마(`GET/PUT /admin/realms/{realm}/users/profile`)에 등록 안 된 속성은 조용히 무시됨** (에러도 안 남). 실제로 `member_cms_username`/`payment_cms_username`을 API로 넣으려다 이 문제로 계속 저장 안 되는 걸 겪었음 — User Profile에 두 속성을 명시적으로 추가한 뒤에야 정상 저장됨.

→ **실무에서 마이그레이션 스크립트를 Admin API 기반으로 짤 때 반드시 먼저 처리해야 하는 단계.**

### 비밀번호/OTP는 평문으로 저장되지 않음

`credential` 테이블의 `type=password` row:
```json
credential_data: {"algorithm":"argon2", "hashIterations":5, ...}
secret_data: {"value":"<해시값>", "salt":"<랜덤값>"}
```
평문 비밀번호는 어디에도 없고, `비밀번호+salt`를 argon2로 해싱한 결과값만 저장됨 (로그인 시 같은 방식으로 다시 해싱해서 값을 비교). `type=otp` row에는 TOTP 공유 비밀키(QR코드에 담긴 값)가 저장됨.

### username과 email은 별개 컬럼

`user_entity.username`과 `user_entity.email`은 서로 다른 필드 (예: `username="lee"`, `email="lee@example.com"`). realm 설정에 따라 로그인 시 둘 중 하나로도 로그인 가능하게 할 수 있지만, 저장되는 `username` 값 자체가 바뀌는 건 아님.

## 8. Custom Authenticator SPI — 전화/카카오 MFA

키클록 기본 MFA는 TOTP(구글 OTP류)뿐이라, 실제 전화/카카오톡 인증을 붙이려면 직접 자바 플러그인(Authenticator SPI)을 만들어야 함. `keycloak/extensions/phone-mfa-authenticator/`가 그 구현체 — 실제 SMS/카카오 게이트웨이 계정이 없어서 발송 부분만 로그 출력(stub)으로 대체했고, 나머지 생명주기(코드 생성 → 도전 폼 → 제출 검증 → 만료/재시도 제한)는 실제 프로덕션과 동일한 구조.

**핵심 클래스**
- `PhoneMfaAuthenticator`: `authenticate()`에서 유저의 `phone_number` 속성으로 6자리 코드를 만들어 `AuthenticationSession`의 authNote에 저장하고 `MessageSender`로 발송, `phone-otp-form.ftl` 챌린지 폼을 띄움. `action()`에서 제출된 코드를 검증 — 만료됐으면 에러, 틀리면 시도횟수 증가(기본 5회 초과 시 실패), 재발송 버튼을 누르면 새 코드로 교체.
- `PhoneMfaAuthenticatorFactory`: SPI 등록점. `code.ttl.seconds`/`max.attempts`를 Admin Console에서 조정 가능한 설정으로 노출.
- `MessageSender`: 실제 SMS/카카오 클라이언트로 교체할 단 하나의 지점. 현재는 `LoggingMessageSender`가 `docker compose logs keycloak`에 코드를 찍는 걸로 대체.

**패키징**: `keycloak/Dockerfile`이 이 모듈을 Maven으로 빌드해서 나온 jar를 키클록 이미지의 `/opt/keycloak/providers/`에 넣음 (`docker-compose.yml`의 `keycloak` 서비스가 `image` 대신 `build: ./keycloak`을 쓰는 이유). `start-dev`는 `providers/`에 새 jar가 있으면 기동 시 자동으로 재증강(auto re-augmentation)하므로 별도 `kc.sh build` 실행은 불필요.

**적용 대상**: `modern-cms`(GCP Cloud Run 대역)만 `browser-phone-mfa-required` flow로 바인딩 — 실제로 그 CMS가 전화/카카오 MFA로 전환됐다는 시나리오를 그대로 반영. member-cms/payment-cms(IDC, ID/PW만)는 기존 flow 그대로라 영향 없음. TOTP 기반 `browser-otp-required`(6번 섹션)는 JSON에는 남아있지만 현재 어떤 클라이언트에도 바인딩되어 있지 않음 — 비교용으로 남겨둠.

**함정**: 이 flow를 Admin API로 조립할 때 `browser` flow를 복제(`/copy`)하면 내부에 "Browser - Conditional OTP"라는 CONDITIONAL 서브플로우가 함께 복제됨. 이걸 단순히 `DISABLED`로 바꾸기만 하면 `AuthenticationFlowException`이 나면서 로그인 자체가 깨짐 — DISABLED가 아니라 그 실행(execution) 자체를 **삭제**해야 함.

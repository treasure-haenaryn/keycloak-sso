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

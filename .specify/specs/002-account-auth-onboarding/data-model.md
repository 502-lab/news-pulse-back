# Data Model: 계정·인증·온보딩·인가

**Feature**: 002-account-auth-onboarding  
**Date**: 2026-06-11  
**Migration**: `V2__account_auth_schema.sql` (001 테이블 수정 없음, 추가만)

---

## 마이그레이션 전략

- Flyway 파일: `src/main/resources/db/migration/V2__account_auth_schema.sql`
- `ddl-auto=validate` 유지 — Hibernate가 런타임에 스키마 정합성 검증
- 001 테이블(`articles`, `sources`, `article_sources`, `summaries`, `source_daily_usage`)은 **건드리지 않음**
- 001의 `AdminPipelineController`가 사용하는 경로(`/api/v1/admin/**`)에 ADMIN 접근 제어만 추가 (코드 변경, 마이그레이션 불필요)

---

## 엔티티 상세

### 1. accounts

```sql
CREATE TABLE accounts (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                VARCHAR(255) UNIQUE,          -- 소셜 전용 계정은 nullable (중계 이메일 포함)
    password_hash        VARCHAR(255),                 -- 이메일 가입 계정만 존재
    role                 VARCHAR(20) NOT NULL DEFAULT 'USER',   -- USER | ADMIN
    status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | SUSPENDED | DELETED(예약)
    email_verified       BOOLEAN NOT NULL DEFAULT FALSE,
    onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE,
    signup_type          VARCHAR(20) NOT NULL,         -- EMAIL | SOCIAL
    failed_login_count   SMALLINT NOT NULL DEFAULT 0,
    locked_until         TIMESTAMP WITH TIME ZONE,     -- 잠금 해제 시각 (NULL = 잠금 없음)
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_accounts_email ON accounts(LOWER(email));
```

**상태 머신**:
- `ACTIVE` → `SUSPENDED` (spec 008에서 관리자 정지 플로우 구현)
- `ACTIVE` → `DELETED` (spec 008에서 탈퇴 플로우 구현; 이 사이클은 값만 예약)
- `email_verified`: `FALSE`(가입 직후) → `TRUE`(코드 검증 성공 또는 소셜 가입)
- 계정 잠금: `failed_login_count >= 5` & `locked_until > now()` → 잠금 상태

**참고**:
- 이메일은 대소문자 불구분 (`LOWER(email)` 인덱스)
- `password_hash`: BCrypt 기본 강도 10 이상 사용

---

### 2. social_connections

```sql
CREATE TABLE social_connections (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    provider         VARCHAR(20) NOT NULL,  -- KAKAO | GOOGLE | APPLE
    provider_user_id VARCHAR(255) NOT NULL,
    connected_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_user_id)
);

CREATE INDEX idx_social_connections_account ON social_connections(account_id);
```

**참고**:
- `provider_user_id`: Kakao=숫자 ID, Google=sub, Apple=sub
- 한 계정에 여러 provider 연결 가능 (이 사이클에서 UI는 없으나 스키마는 지원)

---

### 3. terms_versions

```sql
CREATE TABLE terms_versions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type             VARCHAR(30) NOT NULL,   -- SERVICE | PRIVACY | MARKETING
    version          VARCHAR(50) NOT NULL,
    effective_date   DATE NOT NULL,
    is_required      BOOLEAN NOT NULL DEFAULT TRUE,
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (type, version)
);
```

**초기 데이터 (Flyway 시드)**:
```sql
INSERT INTO terms_versions (type, version, effective_date, is_required, is_active) VALUES
  ('SERVICE',   '1.0', '2026-06-01', TRUE,  TRUE),
  ('PRIVACY',   '1.0', '2026-06-01', TRUE,  TRUE),
  ('MARKETING', '1.0', '2026-06-01', FALSE, TRUE);
```

---

### 4. consent_records

```sql
CREATE TABLE consent_records (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    terms_version_id    UUID NOT NULL REFERENCES terms_versions(id),
    agreed              BOOLEAN NOT NULL,
    agreed_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (account_id, terms_version_id)
);

CREATE INDEX idx_consent_records_account ON consent_records(account_id);
```

---

### 5. refresh_tokens

```sql
CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id   UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    family_id    UUID NOT NULL,              -- 기기 세션 체인 (Rotation 시 유지)
    token_hash   VARCHAR(64) NOT NULL UNIQUE, -- SHA-256(raw token), hex 64자
    device_id    VARCHAR(255),               -- 기기 식별자 (선택, UX 목적)
    issued_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at  TIMESTAMP WITH TIME ZONE,   -- Rotation 시 소비 시각
    is_revoked   BOOLEAN NOT NULL DEFAULT FALSE -- Blast 등 강제 무효화
);

CREATE INDEX idx_refresh_tokens_account ON refresh_tokens(account_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash);
```

**라이프사이클**:
1. 로그인/가입: 새 `family_id`(UUID) 생성, 토큰 발급
2. Rotation: `consumed_at = now()` 설정 + 같은 `family_id`로 새 토큰 발급
3. Grace period 재사용: `consumed_at` 기준 30초 이내 → 같은 family의 최신 active 토큰 반환
4. Blast: `UPDATE refresh_tokens SET is_revoked=TRUE WHERE family_id=?` (family blast)
5. Account blast: `UPDATE refresh_tokens SET is_revoked=TRUE WHERE account_id=?`

**유효 조건**: `consumed_at IS NULL AND is_revoked=FALSE AND expires_at > now()`

---

### 6. verification_codes

```sql
CREATE TABLE verification_codes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    purpose             VARCHAR(30) NOT NULL,  -- EMAIL_VERIFY | PASSWORD_RESET
    code_hash           VARCHAR(64) NOT NULL,  -- SHA-256(6자리 숫자 코드), hex 64자
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    attempt_count       SMALLINT NOT NULL DEFAULT 0,
    hourly_count        SMALLINT NOT NULL DEFAULT 0, -- 현재 1시간 window 내 발급 횟수
    window_start        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(), -- 시간 window 시작
    is_used             BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_verification_codes_account_purpose 
    ON verification_codes(account_id, purpose);
```

**정책 (FR-010/FR-023)**:
- 만료: 15분 (`expires_at = now() + interval '15 minutes'`)
- 재전송 상한: 시간당 5회 (`hourly_count < 5` 검사, 발송 성공 시에만 차감)
- 오입력 상한: 5회 (`attempt_count >= 5` → `is_used=TRUE` 강제 무효화)
- 새 코드 요청 시 기존 동일 purpose 코드 즉시 무효화 (`is_used=TRUE`)
- `window_start`가 1시간 이상 지난 경우 `hourly_count` 초기화

**변경 허가 토큰 (US5)**: 코드 검증 성공 후 단일 사용 변경 허가 토큰은 **별도 DB 저장 없이 짧은 TTL(10분)의 서명된 JWT**로 발급. Claims: `{ sub: accountId, purpose: "PASSWORD_RESET", jti: UUID }`. 사용 후 `is_used=TRUE`로 표시 (코드 레코드에서 추적).

---

### 7. user_profiles

```sql
CREATE TABLE user_profiles (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    nickname   VARCHAR(50),
    age_group  VARCHAR(20),   -- TEENS | TWENTIES | THIRTIES | FORTIES | FIFTIES_PLUS
    occupation VARCHAR(100),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
```

---

### 8. user_interests

```sql
CREATE TABLE user_interests (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    category   VARCHAR(50) NOT NULL,  -- POLITICS | ECONOMY | TECH | CULTURE | SPORTS | WORLD | SCIENCE | HEALTH | ...
    UNIQUE (account_id, category)
);

CREATE INDEX idx_user_interests_account ON user_interests(account_id);
```

**참고**: 관심 카테고리 3개 이상 → `accounts.onboarding_completed` = TRUE 조건 중 하나.

---

### 9. follow_keywords

```sql
CREATE TABLE follow_keywords (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    keyword     VARCHAR(100) NOT NULL,
    type        VARCHAR(20) NOT NULL,  -- COMPANY | THEME | PERSON
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_follow_keywords_account ON follow_keywords(account_id);
```

---

### 10. reading_preferences

```sql
CREATE TABLE reading_preferences (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      UUID NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    summary_depth   VARCHAR(20) NOT NULL DEFAULT 'BALANCED',  -- BRIEF | BALANCED | DEEP
    consume_mode    VARCHAR(20) NOT NULL DEFAULT 'READ',      -- READ | LISTEN | BOTH
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
```

---

### 11. briefing_settings

```sql
CREATE TABLE briefing_settings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    briefing_time       TIME NOT NULL DEFAULT '08:00',  -- HH:MM 로컬 기준
    timezone_offset     SMALLINT NOT NULL DEFAULT 540,  -- UTC 오프셋(분 단위), KST=540
    voice_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    push_agreed         BOOLEAN NOT NULL DEFAULT FALSE,
    push_agreed_at      TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
```

---

## 엔티티 관계 요약

```
accounts (1) ──< social_connections
accounts (1) ──< consent_records >── terms_versions
accounts (1) ──< refresh_tokens
accounts (1) ──< verification_codes
accounts (1) ──  user_profiles       (1:1)
accounts (1) ──< user_interests
accounts (1) ──< follow_keywords
accounts (1) ──  reading_preferences (1:1)
accounts (1) ──  briefing_settings   (1:1)
```

---

## 초기 ADMIN 계정 Seed

```sql
-- V2__account_auth_schema.sql 마지막 또는 V3__seed_admin.sql 별도 분리
-- 비밀번호 해시는 Flyway placeholder ${admin-password-hash} 로 주입
-- application.yaml: flyway.placeholders.admin-email=...
--                   flyway.placeholders.admin-password-hash=...
INSERT INTO accounts (email, password_hash, role, status, email_verified, signup_type)
VALUES (
    '${admin-email}',
    '${admin-password-hash}',
    'ADMIN',
    'ACTIVE',
    TRUE,
    'EMAIL'
) ON CONFLICT (email) DO NOTHING;
```

**대안**: 애플리케이션 기동 시 `CommandLineRunner`로 환경변수(`ADMIN_EMAIL`, `ADMIN_PASSWORD`) 읽어 조건부 생성. Flyway placeholder 방식보다 유연하나 마이그레이션 이력과 분리됨.

**채택**: Flyway seed + `ON CONFLICT DO NOTHING` — 재실행 안전성 보장, 마이그레이션 히스토리 추적 가능.

---

## 감사 로그 (FR-028)

이 사이클에서는 **구조화 로그**를 통해 감사를 구현한다. 별도 audit_logs 테이블은 스펙 008에서 고려.

```
보안 이벤트 로그 항목 (SLF4J MDC + structured JSON):
  event_type: LOGIN_FAILED | ACCOUNT_LOCKED | PASSWORD_CHANGED |
              TOKEN_REUSE_DETECTED | ADMIN_ACCESS | ROLE_CHANGED
  account_id: UUID (PII 비노출 — 이메일 X)
  ip_address: masked or partial (선택)
  timestamp: ISO-8601 UTC
  결코 기록하지 않는 것: 비밀번호, 코드 원문, 토큰 원문, 이메일
```

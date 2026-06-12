# Quickstart: 개인화 피드 · 검색 · 저장 검증 가이드

**Feature**: 003-personalized-feed-search-save  
**Date**: 2026-06-12  
**References**: [spec.md](spec.md) · [data-model.md](data-model.md) · [contracts/openapi.yaml](contracts/openapi.yaml)

---

## 사전 요구사항

1. Docker Desktop 실행 중 (Testcontainers 사용)
2. 001/002 마이그레이션이 적용된 PostgreSQL 인스턴스 (`./gradlew bootRun` 또는 Testcontainers)
3. 002 계정 생성 + 이메일 인증 완료 + 관심사 설정 완료된 테스트 계정

---

## 빠른 검증 실행

```bash
# 전체 테스트 (Docker 필요)
./gradlew test

# 003 관련 테스트만
./gradlew test --tests "*FeedIntegrationTest*"
./gradlew test --tests "*SearchIntegrationTest*"
./gradlew test --tests "*SavedArticleIntegrationTest*"
./gradlew test --tests "*FeedServiceTest*"
./gradlew test --tests "*ArticleRepositorySearchTest*"
```

---

## 검증 시나리오

### 시나리오 1: 개인화 피드 — 관심사 기반 랭킹

**목적**: 관심 카테고리·키워드 기사가 비관심 기사보다 상위에 노출되는지 검증

**사전 상태**:
- 계정 A: 관심사 = [ECONOMY, TECH], 팔로우 키워드 = ["인공지능"]
- 기사 a1: category=ECONOMY, title="경제 성장", publishedAt=1시간 전
- 기사 a2: category=SPORTS, title="야구 경기 결과", publishedAt=30분 전
- 기사 a3: category=TECH, title="인공지능 반도체 개발", publishedAt=2시간 전

**호출**:
```
GET /api/v1/feed
Authorization: Bearer {account_A_token}
```

**기대 결과**:
```json
{
  "personalized": true,
  "articles": [
    { "id": "a3", "rankScore": 130.0, ... },  // TECH(+50) + 키워드 "인공지능"(+30) + recency
    { "id": "a1", "rankScore": 83.3,  ... },  // ECONOMY(+50) + recency(30분 적게)
    { "id": "a2", "rankScore": 16.7,  ... }   // 비관심(+0) + recency만
  ],
  "nextCursor": "...",
  "hasNext": false
}
```

→ a3(TECH + 키워드) > a1(ECONOMY) > a2(SPORTS) 순서 단언

---

### 시나리오 2: 개인화 피드 — 관심사 없는 사용자 Fallback

**목적**: 관심사가 없는 신규 사용자에게 최신순 fallback이 동작하는지 검증

**사전 상태**:
- 계정 B: 관심사 없음, 팔로우 키워드 없음
- 기사 여러 건 존재

**호출**:
```
GET /api/v1/feed
Authorization: Bearer {account_B_token}
```

**기대 결과**:
```json
{
  "personalized": false,
  "articles": [ /* 최신순 정렬 */ ],
  "hasNext": true
}
```

→ `personalized: false` 단언, 기사 목록이 최신순으로 정렬됨 단언

---

### 시나리오 3: 피드 카테고리 필터

**목적**: `?category=TECH`로 TECH 기사만 필터링되는지 검증

**호출**:
```
GET /api/v1/feed?category=TECH
Authorization: Bearer {account_A_token}
```

**기대 결과**:
- 응답의 모든 `articles[].category == "TECH"` 단언
- ECONOMY/SPORTS 기사 미포함 단언
- TECH 기사 내에서 키워드·최신성 랭킹 순 정렬

---

### 시나리오 4: 요약 슬롯 Fallback

**목적**: 선호 DEEP이 미생성이고 BALANCED가 있을 때 fallback이 동작하는지 검증

**사전 상태**:
- 계정 C: reading_preferences.summary_depth = DEEP
- 기사 a4: summaries에 BALANCED만 존재 (DEEP 미생성)

**호출**:
```
GET /api/v1/feed
Authorization: Bearer {account_C_token}
```

**기대 결과** (a4 항목):
```json
{
  "summary": {
    "text": "BALANCED 요약 내용",
    "depth": "balanced",
    "isFallback": true
  }
}
```

→ `isFallback: true`, `depth: "balanced"` 단언

---

### 시나리오 5: 한국어 검색 — 기본 동작

**목적**: 한국어 키워드로 관련 기사가 검색되는지 검증

**사전 상태**:
- 기사 b1: title="한국 경제성장률 발표"
- 기사 b2: title="미국 금리 정책 변화"

**호출**:
```
GET /api/v1/articles/search?q=경제
Authorization: Bearer {account_A_token}
```

**기대 결과**:
- b1 포함 단언 ("경제성장" trigram이 "경제" 매칭)
- b2 미포함 또는 낮은 순위
- `articles` 배열 비어있지 않음 단언

---

### 시나리오 6: 검색 — 0건 결과 및 유효성 오류

**목적**: 검색 결과 없음 처리 및 입력 검증 동작 확인

**6a. 결과 없음**:
```
GET /api/v1/articles/search?q=존재하지않는키워드1234567890
```
→ `{ "articles": [], "hasNext": false }` (200)

**6b. 빈 쿼리**:
```
GET /api/v1/articles/search?q=
```
→ `{ "code": "INVALID_SEARCH_QUERY" }` (422)

**6c. 100자 초과**:
```
GET /api/v1/articles/search?q={101자 문자열}
```
→ 422

---

### 시나리오 7: 기사 저장 CRUD — 멱등성 포함

**목적**: 저장/목록/해제 전체 흐름 및 멱등 동작 검증

**7a. 저장 (신규)**:
```
POST /api/v1/articles/{a1_id}/save
Authorization: Bearer {account_A_token}
```
→ 201, 저장 목록 조회 시 a1 포함

**7b. 저장 (재저장, 멱등)**:
```
POST /api/v1/articles/{a1_id}/save
Authorization: Bearer {account_A_token}
```
→ 200, DB에 중복 레코드 없음 단언

**7c. 목록 조회 (저장 시간 역순)**:
```
GET /api/v1/me/saved-articles
Authorization: Bearer {account_A_token}
```
→ 200, a1 포함, `savedAt` 역순 정렬 단언

**7d. 저장 해제**:
```
DELETE /api/v1/articles/{a1_id}/save
Authorization: Bearer {account_A_token}
```
→ 204, 재조회 시 a1 미포함 단언

**7e. 해제 (미저장, 멱등)**:
```
DELETE /api/v1/articles/{a1_id}/save
Authorization: Bearer {account_A_token}
```
→ 204 (에러 없음)

---

## 인증 오류 검증 (공통)

모든 엔드포인트에 대해:
- 토큰 미포함: `GET /api/v1/feed` → 401
- 이메일 미인증 토큰: `GET /api/v1/feed` → 403

---

## pg_trgm 확장 확인

```sql
-- PostgreSQL에서 확인
SELECT * FROM pg_extension WHERE extname = 'pg_trgm';
-- 또는
\dx pg_trgm
```

→ 행이 반환되면 확장 활성화 확인 완료

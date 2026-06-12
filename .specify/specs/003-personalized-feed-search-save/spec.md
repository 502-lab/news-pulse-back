# Feature Specification: 개인화 피드 · 검색 · 저장

**Feature Branch**: `003-personalized-feed-search-save`

**Created**: 2026-06-12

**Status**: Draft

## 개요

인증된 사용자에게 관심 카테고리·팔로우 키워드 기반으로 랭킹된 개인화 뉴스 피드를 제공하고,
전문(full-text) 키워드 검색과 기사 저장 기능을 제공한다.
001(뉴스 수집)과 002(계정·인증)를 기반으로 구축하며 새 수집 로직은 추가하지 않는다.

---

## User Scenarios & Testing

### User Story 1 - 개인화 피드 조회 (Priority: P1)

인증된 사용자가 자신의 관심 카테고리와 팔로우 키워드에 맞는 기사 목록을 랭킹순으로 조회한다.
피드는 001의 기사 풀을 규칙 기반 가중치(카테고리 일치·키워드 일치·최신성)로 정렬하여 반환한다.

**Why this priority**: 앱의 핵심 가치. 개인화 피드 없이는 일반 목록과 차별화되지 않는다.

**Independent Test**: 관심 카테고리와 팔로우 키워드가 설정된 계정으로 피드 API를 호출하면,
해당 카테고리/키워드 기사가 최신 기사보다 높은 위치에 노출되는 것을 단언할 수 있다.

**Acceptance Scenarios**:

1. **Given** 관심 카테고리 3개 이상이 설정된 인증 사용자, **When** `GET /api/v1/feed` 호출, **Then** 관심 카테고리 기사가 비관심 기사보다 상위에 정렬된 커서 페이지네이션 목록 반환 (200)
2. **Given** 팔로우 키워드가 포함된 기사와 미포함 기사가 동일 날짜에 존재, **When** 피드 조회, **Then** 키워드 포함 기사가 더 높은 점수를 가져 상위 노출
3. **Given** 개인화 결과가 0건(신규 사용자 or 매칭 기사 없음), **When** 피드 조회, **Then** 최신 기사 순 fallback 목록 반환 (200), 응답에 `personalized: false` 포함
4. **Given** 미인증 사용자, **When** `GET /api/v1/feed` 호출, **Then** 401 반환
5. **Given** 인증됐으나 이메일 미인증 사용자, **When** 피드 조회, **Then** 403 반환
6. **Given** 피드 조회 시 커서 파라미터 전달, **When** 다음 페이지 요청, **Then** 이전 결과와 중복 없는 다음 페이지 반환
7. **Given** 인증 사용자가 `?category=IT` 파라미터 지정, **When** 피드 조회, **Then** IT 카테고리 기사만 반환되고 그 안에서 키워드·최신성 가중치 랭킹 순 정렬 (200)

---

### User Story 2 - 기사 검색 (Priority: P2)

인증된 사용자가 키워드를 입력해 기사 제목·요약·본문을 대상으로 전문 검색을 수행한다.
한국어 품질이 핵심 고려사항이다.

**Why this priority**: 피드에 노출되지 않는 관심 기사를 직접 찾는 경로로, 사용자 retention에 기여.

**Independent Test**: 특정 키워드가 포함된 기사를 검색하면 해당 기사가 결과에 포함되고,
키워드 미포함 기사는 결과에서 제외되는 것을 단언할 수 있다.

**Acceptance Scenarios**:

1. **Given** 인증 사용자, **When** `GET /api/v1/articles/search?q=키워드` 호출, **Then** 제목·요약·본문에 해당 키워드를 포함하는 기사 목록 반환 (200), relevance 순 정렬
2. **Given** 검색 결과가 0건, **When** 검색 요청, **Then** 빈 목록 반환 (200), 별도 fallback 없음
3. **Given** 빈 쿼리 문자열 또는 공백만 있는 검색어, **When** 검색 요청, **Then** 422 반환
4. **Given** 검색어 길이가 100자 초과, **When** 검색 요청, **Then** 422 반환
5. **Given** 미인증 사용자, **When** 검색 요청, **Then** 401 반환
6. **Given** 검색 결과가 다수, **When** 커서 파라미터로 페이지네이션, **Then** 중복 없는 다음 페이지 반환

---

### User Story 3 - 기사 저장 (Priority: P3)

인증된 사용자가 기사를 저장하고, 저장된 기사 목록을 조회하며, 저장을 해제할 수 있다.

**Why this priority**: 나중에 읽기 기능. 피드·검색 위에 부가적으로 제공되는 기능.

**Independent Test**: 기사를 저장한 후 저장 목록을 조회하면 해당 기사가 포함되고,
저장 해제 후 목록을 다시 조회하면 제거된 것을 단언할 수 있다.

**Acceptance Scenarios**:

1. **Given** 인증 사용자, **When** `POST /api/v1/articles/{articleId}/save` 호출, **Then** 저장 완료 (201), 저장된 기사는 목록 조회에 포함됨
2. **Given** 이미 저장된 기사를 재저장 시도, **When** `POST /api/v1/articles/{articleId}/save` 호출, **Then** 멱등 처리 — 200 반환 (이미 저장됨 상태 그대로)
3. **Given** 존재하지 않는 articleId로 저장 시도, **When** 저장 요청, **Then** 404 반환
4. **Given** 저장된 기사가 있는 인증 사용자, **When** `GET /api/v1/me/saved-articles` 호출, **Then** 저장된 기사 목록 반환 (200), 저장 시간 역순 정렬
5. **Given** 인증 사용자, **When** `DELETE /api/v1/articles/{articleId}/save` 호출, **Then** 저장 해제 (204), 목록에서 제거됨
6. **Given** 저장하지 않은 기사를 저장 해제 시도, **When** 해제 요청, **Then** 멱등 처리 — 204 반환

---

### Edge Cases

- **개인화 fallback**: 관심사가 없거나 매칭 기사가 0건이면 최신 기사 순 반환, `personalized: false` 표시
- **피드 vs 일반 목록**: `GET /api/v1/articles`(001의 일반 목록, 002에서 인증 게이팅됨)와 `GET /api/v1/feed`(003 개인화 피드)는 별도 엔드포인트로 유지. 일반 목록은 수집 순 정렬, 개인화 피드는 랭킹 점수 순 정렬.
- **삭제된 기사 저장 목록**: 수집 후 만료된(soft-deleted) 기사가 저장 목록에 있으면 목록 조회 시 응답에 `deleted: true` 플래그를 포함하거나 제외 — 이는 001의 기사 만료 정책과 연동 (기본값: 제외)
- **요약 표시**: 피드/검색 응답의 요약은 선호 슬롯 우선 → 다른 슬롯 fallback → null 순으로 반환. 응답에 `isFallback` 플래그 포함. 선호=DEEP·DEEP 미생성·BALANCED 존재 시 BALANCED 반환 + `isFallback=true`.
- **랭킹 동점 처리**: 가중치 점수가 동일한 기사는 `published_at` 내림차순으로 정렬

---

## Requirements

### Functional Requirements

**피드**

- **FR-001**: 인증된(이메일 인증 완료) 사용자는 `GET /api/v1/feed`로 개인화 피드를 조회할 수 있어야 한다.
- **FR-002**: 개인화 피드는 사용자의 관심 카테고리 일치·팔로우 키워드 일치·기사 최신성을 기준으로 규칙 기반 가중치 점수를 계산하여 내림차순 정렬한다.
- **FR-002a**: 피드는 선택적 `category` 쿼리 파라미터를 지원한다(`GET /api/v1/feed?category={CATEGORY}`). 카테고리 값은 001 `Category` enum 정본(POLITICS/ECONOMY_FINANCE/ENTERTAINMENT_CULTURE/SPORTS/WORLD/SCIENCE/HEALTH_MEDICINE/AUTOMOTIVE/IT/OTHER)이며, 사용자의 관심사 외 카테고리도 허용한다. `category` 미지정(기본 "추천") 시: 전체 기사 풀을 대상으로 관심사 가중치 랭킹 적용(soft 랭킹 — 비관심 기사도 포함되나 관심 기사가 상위 노출). `category` 지정 시: 해당 카테고리로 후보를 먼저 필터링한 뒤, 그 안에서 키워드·최신성 가중치로 랭킹.
- **FR-003**: 피드는 001의 cursor 기반 페이지네이션을 그대로 사용하며, 페이지 크기 기본값은 20이다.
- **FR-004**: fallback(최신 기사 순 반환 + `personalized: false`)은 `category` 미지정 기본 추천 피드에만 적용한다. `category` 지정 피드는 해당 카테고리 결과를 그대로 반환하며, 결과가 0건이면 빈 목록을 반환한다(최신 fallback 없음).
- **FR-005**: 피드/검색 응답의 각 기사 요약은 `reading_preferences.summary_depth` 슬롯을 우선 반환한다. 해당 슬롯 미생성 시 생성된 다른 슬롯으로 fallback하여 반환하고, 어떤 슬롯도 없으면 null을 반환한다. 응답 요약 객체는 `text`(요약 본문), `depth`(실제 반환된 슬롯), `isFallback`(실제 depth ≠ 선호 depth 여부) 필드를 포함한다. `text=null`이면 요약 미생성 상태. 단언 포인트: 선호=DEEP이고 DEEP 미생성·BALANCED 존재 시 → `text`=BALANCED 내용, `depth`="balanced", `isFallback`=true.
- **FR-006**: `GET /api/v1/articles`(일반 목록)와 `GET /api/v1/feed`(개인화 피드)는 별도 엔드포인트로 유지한다.

**검색**

- **FR-007**: 인증된 사용자는 `GET /api/v1/articles/search?q={query}`로 기사를 검색할 수 있어야 한다. 검색 대상은 001 보존 정책 내 기사(≤90일)에 한하며, 보존 기간 경과 기사는 검색 풀에서 제외된다.
- **FR-008**: 검색 대상 필드는 기사 제목(title), 요약(summary 텍스트), 본문(content)이다.
- **FR-009**: 검색은 한국어 전문 검색 품질을 지원해야 한다. 구체적 방식은 구현 계획에서 결정한다.
- **FR-010**: 검색어는 2자 이상 100자 이하이어야 하며, 빈 문자열·공백만 있는 쿼리 및 1자 쿼리는 거부한다. (pg_bigm bigram 하한)
- **FR-011**: 검색 결과는 relevance 순으로 정렬되며 cursor 기반 페이지네이션을 지원한다.
- **FR-012**: 검색 결과가 0건이면 빈 목록을 반환한다(별도 fallback 없음).
- **FR-013**: 검색 결과는 relevance(쿼리 매칭) 점수만으로 정렬하며, 개인화 가중치(관심 카테고리·키워드)를 적용하지 않는다. 개인화는 피드의 책임이고 검색은 명시적 쿼리에 충실한다.

**저장**

- **FR-014**: 인증된 사용자는 기사를 저장(`POST /api/v1/articles/{articleId}/save`)하고, 저장 목록을 조회(`GET /api/v1/me/saved-articles`)하며, 저장을 해제(`DELETE /api/v1/articles/{articleId}/save`)할 수 있다.
- **FR-015**: 저장 작업은 멱등해야 한다: 이미 저장된 기사 재저장 시 200, 저장되지 않은 기사 해제 시 204.
- **FR-016**: 존재하지 않는 기사 저장 시도 시 404를 반환한다.
- **FR-017**: 저장 목록은 저장 시간 역순으로 정렬되며 cursor 기반 페이지네이션을 지원한다.
- **FR-018**: 사용자당 저장 기사 수 상한은 1,000건으로 한다. 초과 시 저장 요청은 409를 반환한다.

**공통**

- **FR-019**: 이 기능의 모든 엔드포인트는 002 기준 JWT 인증 + 이메일 인증 완료 상태를 요구한다.
- **FR-020**: 모든 응답은 기존 `ApiResponse<T>` 래퍼를 사용한다.

---

### Key Entities

- **SavedArticle**: 계정↔기사 저장 관계. `account_id`, `article_id`, `saved_at`. (account_id, article_id) 복합 유니크.
- **Article** (001 기존): `id`, `title`, `content`, `published_at`, `category`, `source_id` 등. 수정 없이 참조.
- **Summary** (001 기존): `article_id`, `depth`(brief/balanced/deep), `text`. 요약 슬롯. 수정 없이 참조.
- **UserInterests** (002 기존): `account_id`, `category`. 개인화 랭킹 인풋.
- **FollowKeyword** (002 기존): `account_id`, `keyword`. 개인화 랭킹 인풋.
- **ReadingPreference** (002 기존): `account_id`, `summary_depth`. 피드 응답 요약 슬롯 결정.

---

## Success Criteria

### Measurable Outcomes

- **SC-001**: 개인화 피드 응답 시간이 95번째 백분위수 기준 1초 이내 (페이지 크기 20 기준)
- **SC-002**: 검색 요청 응답 시간이 95번째 백분위수 기준 2초 이내 (한국어 전문 검색 포함)
- **SC-003**: 관심 카테고리 또는 팔로우 키워드가 설정된 사용자에게 해당 카테고리/키워드 기사가 상위 20건 내에 1건 이상 포함되는 비율이 80% 이상
- **SC-004**: 저장 작업(저장/해제) 응답 시간이 95번째 백분위수 기준 500ms 이내
- **SC-005**: 검색 결과에서 쿼리 키워드를 포함하지 않는 기사가 노출되는 비율 5% 미만 (precision 95% 이상). "포함"의 판정은 한국어 형태소 매칭을 포함해 해석하며, 어간·활용 변형 매칭은 '포함'으로 간주한다(literal 부분문자열 일치만이 아님).
- **SC-006**: 빈 개인화 결과 시 fallback이 정상 동작하여 사용자가 항상 기사 목록을 받는 비율 100%

---

## Assumptions

- 001 기사 수집·저장은 이미 동작 중이며, `articles` 테이블에 기사가 존재한다고 가정.
- 002의 `user_interests`, `follow_keywords`, `reading_preferences`는 이미 저장된 상태로 피드 랭킹에 활용된다.
- 개인화 랭킹은 규칙 기반 가중치 점수(ML 없음)로 구현한다. 가중치 수치는 구현 계획에서 결정.
- 한국어 전문 검색의 구체적인 방식(PostgreSQL FTS, pg_bigm, trigram 등)은 구현 계획에서 결정.
- `GET /api/v1/articles`는 001에서 정의된 일반 목록 엔드포인트이며, 003에서 수정하지 않는다.
- 저장 기사 상한(1,000건)은 운영 부하 방지를 위한 보수적 값이다.
- 만료/삭제된 기사가 저장 목록에 있을 경우 목록에서 제외한다(기사 조회 불가이므로).

---

## Dependencies

- **spec 001**: `articles`, `sources`, `summaries` 테이블. cursor 기반 페이지네이션 패턴. Lazy 요약 생성 로직.
- **spec 002**: `accounts`, `user_interests`, `follow_keywords`, `reading_preferences` 테이블. JWT 인증·이메일 인증 게이팅.

## Out of Scope

- 편향(bias) 분석 반영 — spec 006
- ML 기반 추천 고도화 — 향후
- 소셜 공유 기능
- 기사 코멘트/메모 기능
- 저장 기사 폴더/태그 분류
- 읽음 상태 추적 / 이미 읽은 기사 제외 없음(피드·검색에서 기사 재노출 가능) — 향후

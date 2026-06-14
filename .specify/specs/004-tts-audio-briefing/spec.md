# Feature Specification: TTS 음성 — 기사·브리핑 오디오

**Feature Branch**: `004-tts-audio-briefing`

**Created**: 2026-06-13

**Status**: Draft

**Input**: 기사·브리핑 요약을 AI 음성으로 듣는 기능 — 음성 선택, 기사 TTS, 데일리 브리핑 TTS, 저장 기사 필터, 설정 확장

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — 음성 선택 및 청취 설정 (Priority: P1)

인증된 사용자가 사용 가능한 AI 음성 목록을 조회하고, 미리듣기(짧은 샘플 클립)로 음성을 확인한 뒤 선호 음성과 콘텐츠 소비 방식(읽기·듣기·둘 다)을 프로필에 저장한다.

**Why this priority**: 음성 선택이 선행되지 않으면 TTS 생성 요청(US2), 브리핑(US3) 모두 불가. 모든 음성 기능의 전제 조건.

**Independent Test**: 음성 목록 조회 → 미리듣기 URL 확인 → voiceId·consumeMode 저장 → GET 프로필로 반영 확인. TTS 생성 없이 단독 검증 가능.

**Acceptance Scenarios**:

1. **Given** 인증된 사용자, **When** 음성 목록 API 호출, **Then** 하린(여)·준서(남) 음성 항목(id, 이름, 성별, 미리듣기 URL)이 반환된다
2. **Given** 미리듣기 URL, **When** URL 접속, **Then** 1~5초 분량의 샘플 오디오가 재생된다
3. **Given** 인증된 사용자, **When** voiceId=하린, consumeMode=LISTEN 으로 설정 저장, **Then** 이후 GET 프로필에서 동일 값이 반환된다
4. **Given** 미인증 사용자, **When** 음성 목록 조회, **Then** 401 반환

---

### User Story 2 — 기사 TTS 생성 및 재생 (Priority: P1)

인증된 사용자가 특정 기사의 요약(brief/balanced)을 선택한 음성으로 오디오 변환 요청하면, 시스템이 비동기로 오디오를 생성한다. 사용자는 상태를 폴링하고 READY 상태가 되면 오디오 URL로 재생한다. 동일 기사·동일 음성 조합은 캐싱되어 재요청 시 즉시 반환된다.

**Why this priority**: 앱의 핵심 가치. 음성 청취 모드 사용자가 기사를 소비하는 기본 경로.

**Independent Test**: 기사 TTS 요청 → 상태 폴링(PENDING→READY) → audioUrl 재생 → 동일 요청 재시도 시 캐시 반환(즉시, 새 상태 없음). US1 없이도 voiceId를 요청 파라미터로 받아 단독 테스트 가능.

**Acceptance Scenarios**:

1. **Given** 요약 COMPLETED 기사·인증 사용자, **When** TTS 생성 요청(voiceId 포함), **Then** 202 + jobId + status=PENDING 반환
2. **Given** PENDING TTS 작업, **When** 상태 조회, **Then** PENDING→PROCESSING→READY 순 전이, READY 시 audioUrl·durationSec 포함
3. **Given** READY TTS audioUrl, **When** URL 접속, **Then** 오디오 파일이 재생된다
4. **Given** 이미 READY 상태인 기사+voiceId 조합, **When** 동일 조합으로 TTS 재요청, **Then** 200 + 기존 audioUrl 즉시 반환 (새 작업 생성 없음)
5. **Given** 요약 미완료(PENDING) 기사, **When** TTS 요청, **Then** 409 또는 적절한 에러 코드 반환
6. **Given** 미인증 사용자, **When** TTS 요청, **Then** 401 반환

---

### User Story 3 — 데일리 브리핑 TTS (Priority: P2)

인증된 사용자가 "오늘의 브리핑"을 요청하면, 003 개인화 피드 상위 N건 COMPLETED 기사의 TTS 상태 배열(재생 큐, ttsItems[])을 받는다. 브리핑 재생 큐는 하루 단위로 캐시되어 같은 날 재요청 시 즉시 반환된다.

**Why this priority**: 뉴스 청취 앱의 대표 UX. P1 기사 TTS 이후 자연스러운 확장.

**Independent Test**: 브리핑 요청 → 재생 큐(articleIds + ttsItems[]) 반환 → 같은 날 재요청 시 동일 재생 큐 즉시 반환(DailyBrief 캐시). 003 피드 API와 독립적으로 테스트 가능(최소 기사 수를 직접 삽입).

**Acceptance Scenarios**:

1. **Given** 관심사 설정된 인증 사용자, **When** 오늘의 브리핑 요청, **Then** 상위 N건 기사 ID 목록(재생 큐)과 status=PENDING 반환
2. **Given** 브리핑 재생 큐 조회, **When** 모든 ttsItems 처리 완료, **Then** ttsItems 전체 status=READY + 각 항목 audioUrl 포함, HTTP 200 반환
3. **Given** 오늘 이미 생성된 브리핑, **When** 같은 날 동일 사용자가 재요청, **Then** 캐시된 재생 큐(ttsItems 배열) 즉시 반환 (DailyBrief 재사용, 새 생성 없음)
4. **Given** 관심사 미설정 사용자, **When** 브리핑 요청, **Then** 최신순 상위 N건 fallback 적용
5. **Given** 오늘 날짜 경계(자정), **When** 다음 날 브리핑 요청, **Then** 새 브리핑 생성됨 (전일 캐시 미사용)

---

### User Story 4 — 저장 기사 "들을 수 있음" 필터 (Priority: P3)

003에서 구현한 저장 기사 목록 조회 시, TTS 오디오가 준비된(READY) 기사만 필터링하는 옵션을 제공한다.

**Why this priority**: US2 의존. 저장 기사가 많은 사용자의 편의 기능.

**Independent Test**: 저장 기사 중 일부는 TTS READY, 일부는 없음 → listenable=true 필터 → READY만 반환. 실제 오디오 재생 없이 DB 조인으로 검증 가능.

**Acceptance Scenarios**:

1. **Given** 저장된 기사 A(TTS READY)·B(TTS 없음), **When** `GET /me/saved-articles?listenable=true`, **Then** A만 반환
2. **Given** `listenable` 파라미터 미제공, **When** 저장 목록 조회, **Then** 기존 동작(전체 반환) 유지 (하위 호환)

---

### Edge Cases

- TTS 생성 중 AI 음성 서비스 장애 → status=FAILED, 사용자 재시도 가능
- 요약 텍스트가 없는(미생성) 기사 → TTS 요청 불가, 명확한 에러 반환
- 브리핑 생성 대상 COMPLETED 기사가 0건 → 404 (NO_FEED_ARTICLES)
- 동일 조합 TTS가 PROCESSING 중일 때 재요청 → 기존 작업 반환(중복 생성 금지)
- 오디오는 CloudFront 영구 공개 URL로 제공 (presigned URL 아님). 앱 레벨 URL 만료 처리 불필요 — TTL은 CloudFront 배포 설정으로 관리.
- durationSec 0 또는 미산출 → null 허용(생성 완료 후 백필 가능)

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 사용 가능한 음성 목록(id, 이름, 성별, 미리듣기 URL)을 인증된 사용자에게 제공해야 한다
- **FR-002**: 사용자는 선호 음성(voiceId)을 프로필에 저장할 수 있어야 한다 (readMode 신설 취소 — 기존 consumeMode: READ/LISTEN/BOTH 재사용)
- **FR-003**: 시스템은 002 reading_preferences에 voice_id 컬럼만 추가해야 한다 (readMode 신설 없음 — 기존 consume_mode 재사용)
- **FR-004**: 사용자는 특정 기사 요약(brief/balanced 심도)을 선택 음성으로 TTS 변환 요청할 수 있어야 한다
- **FR-005**: 시스템은 TTS 생성을 비동기로 수행하며, 상태를 PENDING → PROCESSING → READY(또는 FAILED)로 추적해야 한다
- **FR-006**: 동일한 기사·음성 조합의 TTS가 이미 READY 상태이면 재생성 없이 기존 오디오를 반환해야 한다 (멱등 캐시)
- **FR-007**: 동일 조합이 PROCESSING 중일 때 재요청 시 기존 작업을 반환해야 한다 (중복 생성 금지)
- **FR-008**: TTS READY 상태가 되면 오디오 파일 URL과 재생 시간(초)을 응답에 포함해야 한다
- **FR-009**: 시스템은 인증 사용자의 오늘 날짜 데일리 브리핑 TTS를 제공해야 한다
- **FR-010**: 브리핑은 003 개인화 피드 상위 N건(기본값 5건) 중 요약 완료(COMPLETED) 기사를 선정하여 재생 큐(기사별 TTS 상태 배열 ttsItems[])로 반환해야 한다 (단일 concat 오디오 아님)
- **FR-011**: 브리핑 오디오는 날짜 단위로 캐시되어 같은 날 재요청 시 즉시 반환되어야 한다
- **FR-012**: 브리핑 요청 응답에는 재생 큐(기사 목록, 순서)가 포함되어야 한다
- **FR-013**: 사용자는 저장 기사 목록 조회 시 TTS 준비 기사만 필터링할 수 있어야 한다 (하위 호환 옵션 파라미터)
- **FR-014**: 요약이 아직 생성되지 않은 기사의 TTS 요청은 거부되어야 한다
- **FR-015**: 모든 TTS·브리핑 엔드포인트는 로그인 + 이메일 인증을 필요로 한다

### Key Entities

- **Voice**: 음성 캐릭터. id·이름·성별·미리듣기 URL을 보유하며, 시스템에 고정된 목록(하린·준서)
- **TtsAudio**: 생성된 오디오 작업. 소유자 유형(ARTICLE 전용 — BRIEF 없음)·참조 ID·음성 ID·상태·audio_key(S3 키, URL 아님)·재생 시간을 보유. 동일 (ownerType, refId, voiceId) 조합은 단 하나만 존재(캐시 키). audioUrl은 응답 시 audio_key로부터 생성.
- **DailyBrief**: 날짜별 사용자 브리핑. 날짜·사용자·선정된 기사 목록·연결된 TtsAudio를 보유. (사용자, 날짜) 조합으로 유일

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 음성 목록이 1초 이내 사용자에게 표시된다
- **SC-002**: 기사 TTS 오디오가 요청 후 30초 이내 READY 상태가 된다 (단일 요약 기준)
- **SC-003**: 이미 READY인 기사+음성 조합 재요청 시 즉시(1초 이내) 오디오 URL이 반환된다
- **SC-004**: 데일리 브리핑 초회 요청 시 재생 큐(articleIds + ttsItems) 생성은 즉시(< 1초, 202 반환)이고, 각 기사 TTS READY 완료는 요청 후 60초 이내 달성된다 (N=5 기준)
- **SC-005**: 같은 날 브리핑 재요청 시 즉시(1초 이내) 캐시된 결과가 반환된다
- **SC-006**: 사용자는 음성 선택과 설정 저장을 30초 이내 완료할 수 있다
- **SC-007**: TTS FAILED 시 상태가 명확히 노출되어 사용자가 재시도 가능하다

---

## Assumptions

- 음성 캐릭터 "하린(여)"·"준서(남)"은 AI 음성 서비스 내 특정 음성 ID에 매핑되며, 매핑 세부사항은 플랜에서 결정한다
- 미리듣기 URL은 사전 녹음된 정적 샘플이며 CDN에 미리 배포된다 (온디맨드 생성 아님)
- TTS 대상 심도는 brief/balanced에 한정한다 (deep은 텍스트 길이가 길어 TTS 품질·비용 문제로 제외)
- 데일리 브리핑 N=5 (기본값). 환경 설정으로 변경 가능하며 정확한 기본값은 플랜에서 확정한다
- 브리핑 TTS는 기사별 독립 TtsAudio를 재생 큐(ttsItems[])로 반환한다. 단일 파일 concat 없음. TtsAudio는 전 사용자 공유 캐시(동일 기사+음성 조합은 한 번만 생성).
- 오디오 파일은 클라우드 오브젝트 스토리지에 저장되고 CDN을 통해 제공된다 (제공자는 플랜에서 결정)
- TTS 생성 타이밍: **온디맨드(첫 요청 시 생성, 당일 캐시)**로 결정됨. briefTime 사전 생성 옵션 채택 안 함.
- SC-002·SC-004의 정확한 시간 목표는 선택한 TTS 제공자의 레이턴시에 따라 플랜에서 조정될 수 있다
- 이어듣기 위치 저장, 청취 이력 통계는 이번 스코프 밖이다
- 브리핑 큐레이션 로직(트렌드/편집 우선순위)은 이번 스코프 밖이다 (003 피드 상위 N 재사용)
- 003 개인화 피드가 배포·동작 중임을 전제한다

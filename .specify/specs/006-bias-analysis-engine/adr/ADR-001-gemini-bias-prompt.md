# ADR-001: Gemini 편향 분석 프롬프트 설계

**Status**: Accepted
**Date**: 2026-06-22
**Feature**: 006 편향 분석 엔진
**위치 주의**: CLAUDE.md는 ADR을 `/specs/adr/`에 두도록 안내하나 `specs/`는 수정 금지 submodule이므로
feature-local(`.specify/specs/006-bias-analysis-engine/adr/`)에 기록한다.

---

## Context

US2 편향 분석 파이프라인은 Gemini Flash(`gemini-2.0-flash`, 001 AI 요약과 동일 모델)로 기사의
정치적 편향 점수(−100~+100 정수)와 근거 키워드(2~5개)를 받아야 한다. 기존 분류/요약 프롬프트는
단순 문자열을 반환하지만 편향은 복합 값(숫자 + 배열)이라 파싱 신뢰성이 핵심이다.

## Decision

**JSON 구조화 출력 프롬프트**를 채택한다. 프롬프트는 다음 형식만 반환하도록 지시한다:

```
{"score": <-100에서 +100 사이 정수>, "keywords": ["키워드1", "키워드2", ...]}
```

전체 프롬프트(구현 `GeminiAiProvider.BIAS_PROMPT`):

```
다음 뉴스 기사의 정치적 편향성을 분석하세요. 반드시 아래 JSON 형식으로만 답하세요 (다른 텍스트 없이):
{"score": <-100에서 +100 사이 정수>, "keywords": ["키워드1", "키워드2", ...]}
score: -100=극진보, 0=중립, +100=극보수 (한국 뉴스 생태계 기준)
keywords: 편향의 근거 키워드 2~5개
순수 사실 보도(날씨·스포츠 결과 등)는 score=0, keywords=["사실 보도"]

제목: %s
내용: %s
```

### 파싱·검증 규칙 (`GeminiAiProvider.parseBias`)
- 마크다운 코드펜스(```json ... ```)를 제거 후 파싱한다(Gemini가 종종 펜스로 감쌈).
- `score`는 정수이며 −100~+100 범위 검증, 위반 시 `AiProviderException`(결정적 오류, 재시도 비대상).
- `keywords`는 2~5개 검증, 위반 시 `AiProviderException`.
- 429/5xx/타임아웃은 `AiTransientException`(재시도 대상).

## Rationale

- JSON은 숫자+배열 복합 값 파싱에 가장 신뢰도가 높다. Gemini Flash는 JSON 출력 제어가 안정적.
- 결정적 오류(파싱/범위)와 일시 오류(429/5xx)를 예외 타입으로 분리 → 재시도 정책(FR-003)과 일관.
- "한국 뉴스 생태계 기준" 명시로 −100/+100 정규화 기준을 프롬프트에 고정.

## Alternatives Considered

- **첫 줄 점수 + 나머지 키워드(줄 기반)**: 파싱 fragile, 키워드에 줄바꿈 포함 시 깨짐 → 기각.
- **XML 출력**: 불필요한 복잡성 → 기각.

## Consequences

- 프롬프트/파싱 변경 시 본 ADR을 갱신한다(CLAUDE.md: Gemini 프롬프트 변경 시 ADR 기록 필수).
- 실제 Gemini 편향 응답 품질은 런타임/배포 시 검증 대상(테스트는 WireMock 스텁으로 파싱·예외 경로만 검증).

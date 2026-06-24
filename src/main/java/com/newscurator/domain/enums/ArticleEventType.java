package com.newscurator.domain.enums;

/**
 * 기사 행동 이벤트 유형(009 읽기 추적).
 *
 * <p><b>P1에서는 {@link #VIEW}만 생성·사용</b>한다. 나머지는 후속 클라이언트 계측 사이클을 위한
 * forward-seam이며 P1 코드에서 기록하지 않는다(빈 구현 금지).
 */
public enum ArticleEventType {
    /** 기사 상세 조회(P1, 서버 기록). */
    VIEW,
    // --- forward-seam (후속 클라이언트 계약 사이클) ---
    /** 체류시간(클라 계측). */
    DWELL,
    /** 완료율(스크롤% 임계, 클라 계측). */
    COMPLETE,
    /** AI 기능 클릭(클라 계측). */
    AI_CLICK,
    /** 공유(클라 계측). */
    SHARE
}

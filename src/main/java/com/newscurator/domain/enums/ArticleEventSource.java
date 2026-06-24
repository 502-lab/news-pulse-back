package com.newscurator.domain.enums;

/**
 * 기사 행동 이벤트 출처(009 읽기 추적).
 *
 * <p><b>P1에서는 {@link #SERVER}만 사용</b>한다(서버가 상세 조회 시 직접 기록 — 클라 위조 방지).
 * {@link #CLIENT}는 후속 클라이언트 계측 이벤트(체류·완료율·클릭·공유)를 위한 forward-seam.
 */
public enum ArticleEventSource {
    /** 서버 기록(P1). */
    SERVER,
    /** 클라이언트 전송(후속 forward-seam). */
    CLIENT
}

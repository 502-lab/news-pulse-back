package com.newscurator.client.ai;

import com.newscurator.domain.enums.Category;
import com.newscurator.domain.enums.SummaryDepth;

/**
 * AI 공급자 포트 인터페이스.
 * Gemini 구현체는 이 인터페이스 뒤에 숨어 교체 가능.
 */
public interface AiProvider {

    Category classify(String title, String content);

    String summarize(String title, String content, SummaryDepth depth);

    /**
     * 기사 편향 분석. −100(극진보)~+100(극보수) 점수와 근거 키워드 2~5개를 반환한다.
     *
     * @throws com.newscurator.exception.AiTransientException 429/5xx/타임아웃 등 일시 오류 (재시도 대상)
     * @throws com.newscurator.exception.AiProviderException  파싱 실패·결정적 오류 (재시도 비대상)
     */
    BiasAnalysisResult analyzeBias(String title, String content);
}

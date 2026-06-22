package com.newscurator.client.ai;

import java.util.List;

/**
 * 편향 분석 AI 호출 결과.
 *
 * @param value             편향 점수 −100(극진보)~+100(극보수) 정수
 * @param rationaleKeywords 점수 근거 키워드 2~5개
 */
public record BiasAnalysisResult(int value, List<String> rationaleKeywords) {}

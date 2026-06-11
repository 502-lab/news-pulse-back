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
}

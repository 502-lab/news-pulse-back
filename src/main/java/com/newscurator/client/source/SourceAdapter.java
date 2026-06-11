package com.newscurator.client.source;

import com.newscurator.domain.Source;
import com.newscurator.domain.enums.SourceAdapterType;
import java.util.List;

/**
 * 뉴스 출처 어댑터 포트 인터페이스.
 * RSS, NAVER 등 구현체는 이 인터페이스 뒤에 숨어 교체 가능.
 */
public interface SourceAdapter {

    boolean supports(SourceAdapterType adapterType);

    List<ArticleCandidate> fetchCandidates(Source source);
}

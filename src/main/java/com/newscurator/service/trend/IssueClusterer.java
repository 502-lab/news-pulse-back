package com.newscurator.service.trend;

import java.util.List;

/**
 * 이슈 산출 포트 (FR-011). 윈도우 기사+키워드에서 이슈를 재산출한다.
 *
 * <p>MVP 구현은 {@link CoOccurrenceIssueClusterer}(키워드 동시출현). 교체 가능
 * (004 TtsProvider / 005 PushNotificationPort 격리 패턴). v2 EmbeddingIssueClusterer는 범위 밖.
 */
public interface IssueClusterer {

    /** 관련 기사 묶음 + 대표 키워드 + 증감(delta)을 가진 이슈 목록을 재산출한다. */
    List<DerivedIssue> cluster(IssueClusterContext ctx);
}

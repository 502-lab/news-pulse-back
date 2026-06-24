package com.newscurator.service;

import com.newscurator.repository.ArticleEventRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 009 읽기 추적 — 조회 이벤트 기록(US1).
 *
 * <p><b>best-effort 격리</b>: {@code recordView}는 기사 상세(핫패스)와 <b>별개 트랜잭션</b>으로 수행된다.
 * 컨트롤러는 상세 조회가 성공(상세 서비스 @Transactional 커밋)한 <b>뒤</b> 이 메서드를 try-catch로 호출하므로,
 * 기록 실패(예외)가 상세 응답을 깨지 않는다. {@link Propagation#REQUIRES_NEW}는 호출 시점에 ambient TX가
 * 없어 사실상 REQUIRED와 동등하나, "독립 TX 경계"를 코드로 못박는 <b>방어적 명시</b>다(미래에 TX 안에서
 * 호출돼도 격리). 008 AdminAuditService(REQUIRED 참여=같이 롤백)와 반대 방향, 005 NotificationSendService
 * (REQUIRES_NEW 격리)와 동일 계열.
 *
 * <p>디바운스(같은 account·article 30분 1건)는 native 조건부 INSERT가 처리한다(P1=VIEW·SERVER만).
 */
@Service
public class ReadTrackingService {

    private final ArticleEventRepository articleEventRepository;

    public ReadTrackingService(ArticleEventRepository articleEventRepository) {
        this.articleEventRepository = articleEventRepository;
    }

    /**
     * 조회 이벤트 기록(VIEW·SERVER). 30분 디바운스 적용 — 같은 (account, article)의 VIEW가 30분 내
     * 존재하면 기록하지 않는다.
     *
     * @return 기록되면 true, 디바운스로 skip되면 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordView(UUID accountId, Long articleId) {
        return articleEventRepository.insertViewDebounced(accountId, articleId) > 0;
    }
}

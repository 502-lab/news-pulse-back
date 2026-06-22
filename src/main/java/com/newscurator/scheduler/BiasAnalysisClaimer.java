package com.newscurator.scheduler;

import com.newscurator.config.BiasProperties;
import com.newscurator.domain.BiasAnalysis;
import com.newscurator.repository.BiasAnalysisRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 편향 분석 배치 클레임 전담 컴포넌트 (two-tx 모델, research R-013 / 005 NotificationOutboxClaimer 패턴).
 *
 * <p>별도 Spring 빈으로 분리하여 {@code @Transactional}이 AOP 프록시를 통해 정상 적용되도록 한다.
 * {@link #claimBatch(int)}의 TX가 커밋되면 FOR UPDATE SKIP LOCKED 락이 해제되고,
 * 이후 Gemini HTTP 호출이 DB 락 없이 실행된다. 결과는 {@link #persistResult(BiasAnalysis)}가
 * 자체 TX로 저장한다.
 */
@Component
public class BiasAnalysisClaimer {

    private final BiasAnalysisRepository biasAnalysisRepository;
    private final BiasProperties biasProperties;

    public BiasAnalysisClaimer(
            BiasAnalysisRepository biasAnalysisRepository, BiasProperties biasProperties) {
        this.biasAnalysisRepository = biasAnalysisRepository;
        this.biasProperties = biasProperties;
    }

    /**
     * PENDING/stuck-PROCESSING 배치를 PROCESSING + lease로 마킹하고 커밋한다.
     * 반환 시점에 FOR UPDATE 락이 해제된다.
     */
    @Transactional
    public List<BiasAnalysis> claimBatch(int limit) {
        List<BiasAnalysis> batch = biasAnalysisRepository.lockAndClaimPending(limit);
        for (BiasAnalysis row : batch) {
            row.claim(biasProperties.leaseMinutes());
            biasAnalysisRepository.save(row);
        }
        return new ArrayList<>(batch);
    }

    /**
     * One-shot 복구 대상 1건(FAILED·attempt_count=3·failed_at+6h 경과)을 PROCESSING + lease로
     * 마킹하고 커밋한다. attempt_count는 그대로 유지(아직 임계치 내).
     */
    @Transactional
    public Optional<BiasAnalysis> claimOneShotRecovery() {
        Optional<BiasAnalysis> candidate = biasAnalysisRepository.lockOneShotRecoveryCandidate();
        candidate.ifPresent(row -> {
            row.claim(biasProperties.leaseMinutes());
            biasAnalysisRepository.save(row);
        });
        return candidate;
    }

    /** 처리 결과(DONE / PENDING(retry) / FAILED)를 자체 트랜잭션으로 저장한다. */
    @Transactional
    public void persistResult(BiasAnalysis row) {
        biasAnalysisRepository.save(row);
    }
}

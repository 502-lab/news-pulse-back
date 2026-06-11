package com.newscurator.service;

import com.newscurator.domain.Source;
import com.newscurator.repository.SourceRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    private final SourceRepository sourceRepository;
    private final SourceCollectionExecutor sourceCollectionExecutor;

    public CollectionService(
            SourceRepository sourceRepository,
            SourceCollectionExecutor sourceCollectionExecutor) {
        this.sourceRepository = sourceRepository;
        this.sourceCollectionExecutor = sourceCollectionExecutor;
    }

    public void collectAll() {
        List<Source> sources = sourceRepository.findByActiveTrue();
        for (Source source : sources) {
            try {
                sourceCollectionExecutor.collectFromSource(source);
            } catch (Exception e) {
                // 출처별 독립 실행: 출처 A 실패가 출처 B 수집을 막지 않음 (FR-003)
                log.error("[COLLECT] 출처 수집 실패, sourceId={}, name={}: {}",
                        source.getId(), source.getName(), e.getMessage(), e);
                source.recordFailure();
                sourceRepository.save(source);
            }
        }
    }
}

package com.newscurator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.newscurator.domain.Source;
import com.newscurator.repository.SourceRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CollectionServiceTest {

    @Mock
    private SourceRepository sourceRepository;

    @Mock
    private SourceCollectionExecutor sourceCollectionExecutor;

    private CollectionService collectionService;

    @BeforeEach
    void setUp() {
        collectionService = new CollectionService(sourceRepository, sourceCollectionExecutor);
    }

    @Test
    @DisplayName("출처 A 성공, 출처 B 실패 시 A 결과는 유지됨")
    void collectAll_sourceASuccess_sourceBFails_aResultsPersisted() {
        Source sourceA = buildSource(1L, "연합뉴스");
        Source sourceB = buildSource(2L, "Failing Source");

        when(sourceRepository.findByActiveTrue()).thenReturn(List.of(sourceA, sourceB));
        doThrow(new RuntimeException("timeout"))
                .when(sourceCollectionExecutor).collectFromSource(sourceB);

        assertThatNoException().isThrownBy(() -> collectionService.collectAll());

        verify(sourceCollectionExecutor).collectFromSource(sourceA);
        verify(sourceCollectionExecutor).collectFromSource(sourceB);
        // 실패한 출처에 대해 recordFailure + save 호출 확인
        verify(sourceB).recordFailure();
        verify(sourceRepository).save(sourceB);
    }

    @Test
    @DisplayName("활성 출처가 없으면 executor 호출 없음")
    void collectAll_noActiveSources_executorNotCalled() {
        when(sourceRepository.findByActiveTrue()).thenReturn(List.of());

        collectionService.collectAll();

        verifyNoInteractions(sourceCollectionExecutor);
    }

    private Source buildSource(long id, String name) {
        Source source = mock(Source.class);
        when(source.getId()).thenReturn(id);
        when(source.getName()).thenReturn(name);
        return source;
    }
}

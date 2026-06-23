package com.newscurator.repository;

import com.newscurator.domain.ExcludedKeyword;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 제외 키워드 리포지토리(008 FR-032). 트렌드 집계 배제 대상.
 */
public interface ExcludedKeywordRepository extends JpaRepository<ExcludedKeyword, Long> {

    boolean existsByKeyword(String keyword);

    List<ExcludedKeyword> findAllByOrderByCreatedAtDesc();
}

package com.newscurator.repository;

import com.newscurator.domain.ArticleKeyword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleKeywordRepository
        extends JpaRepository<ArticleKeyword, ArticleKeyword.Pk> {

    /** 멱등 삽입: 동일 (article_id, term) 재추출은 no-op. */
    @Modifying
    @Query(value = """
            INSERT INTO article_keyword (article_id, term, created_at)
            VALUES (:articleId, :term, NOW())
            ON CONFLICT (article_id, term) DO NOTHING
            """, nativeQuery = true)
    void insertIgnore(@Param("articleId") Long articleId, @Param("term") String term);
}

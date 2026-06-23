package com.newscurator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 기사별 추출 키워드 (article_id, term). 재추출 멱등(PK). */
@Entity
@Table(name = "article_keyword")
@IdClass(ArticleKeyword.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArticleKeyword {

    @Id
    @Column(name = "article_id")
    private Long articleId;

    @Id
    @Column(name = "term", length = 64)
    private String term;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ArticleKeyword(Long articleId, String term) {
        this.articleId = articleId;
        this.term = term;
        this.createdAt = Instant.now();
    }

    /** 복합키 */
    public static class Pk implements Serializable {
        private Long articleId;
        private String term;

        public Pk() {}

        public Pk(Long articleId, String term) {
            this.articleId = articleId;
            this.term = term;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(articleId, pk.articleId) && Objects.equals(term, pk.term);
        }

        @Override
        public int hashCode() {
            return Objects.hash(articleId, term);
        }
    }
}

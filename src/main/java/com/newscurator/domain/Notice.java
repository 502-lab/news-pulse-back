package com.newscurator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스 공지(008 US4). 게시(published=true) 상태인 것만 공개 노출, 초안은 어드민만 조회.
 */
@Entity
@Table(name = "notice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false)
    private boolean published;

    @Column(name = "author_account_id", nullable = false, columnDefinition = "uuid")
    private UUID authorAccountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    public Notice(String title, String content, boolean published, UUID authorAccountId) {
        this.title = title;
        this.content = content;
        this.published = published;
        this.authorAccountId = authorAccountId;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 제목·본문 수정. */
    public void edit(String title, String content) {
        this.title = title;
        this.content = content;
        this.updatedAt = Instant.now();
    }

    /** 게시 상태 전환(초안↔게시). */
    public void setPublished(boolean published) {
        this.published = published;
        this.updatedAt = Instant.now();
    }
}

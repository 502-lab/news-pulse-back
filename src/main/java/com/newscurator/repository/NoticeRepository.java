package com.newscurator.repository;

import com.newscurator.domain.Notice;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 공지 리포지토리(008 US4). 공개 조회는 게시(published=true)만, 어드민은 전체.
 */
public interface NoticeRepository extends JpaRepository<Notice, Long> {

    /** 공개: 게시된 공지만 최신순. */
    List<Notice> findByPublishedTrueOrderByCreatedAtDesc();

    /** 어드민: 전체(초안 포함) 페이지. */
    Page<Notice> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

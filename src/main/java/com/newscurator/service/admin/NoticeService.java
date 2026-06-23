package com.newscurator.service.admin;

import com.newscurator.domain.Notice;
import com.newscurator.domain.enums.AuditTargetType;
import com.newscurator.exception.AdminTargetNotFoundException;
import com.newscurator.repository.NoticeRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지(Notice) 관리(008 US4). 어드민 CRUD·게시 토글(+감사) / 공개는 게시된 것만.
 */
@Service
public class NoticeService {

    private static final int TITLE_MAX = 200;

    private final NoticeRepository noticeRepository;
    private final AdminAuditService auditService;

    public NoticeService(NoticeRepository noticeRepository, AdminAuditService auditService) {
        this.noticeRepository = noticeRepository;
        this.auditService = auditService;
    }

    /** 공개 조회: 게시(published=true) 공지만. */
    @Transactional(readOnly = true)
    public List<Notice> listPublished() {
        return noticeRepository.findByPublishedTrueOrderByCreatedAtDesc();
    }

    /** 어드민 조회: 초안 포함 전체. */
    @Transactional(readOnly = true)
    public Page<Notice> listAll(Pageable pageable) {
        return noticeRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public Notice get(Long id) {
        return load(id);
    }

    @Transactional
    public Notice create(UUID actorId, String title, String content, boolean published) {
        validate(title, content);
        Notice n =
                noticeRepository.save(
                        Notice.builder()
                                .title(title)
                                .content(content)
                                .published(published)
                                .authorAccountId(actorId)
                                .build());
        auditService.record(
                actorId, "NOTICE_CREATE", AuditTargetType.NOTICE, n.getId().toString(),
                Map.of("published", published));
        return n;
    }

    @Transactional
    public Notice update(UUID actorId, Long id, String title, String content) {
        validate(title, content);
        Notice n = load(id);
        n.edit(title, content);
        auditService.record(
                actorId, "NOTICE_UPDATE", AuditTargetType.NOTICE, id.toString(),
                Map.of("title", title));
        return n;
    }

    @Transactional
    public void setPublished(UUID actorId, Long id, boolean published) {
        Notice n = load(id);
        n.setPublished(published);
        auditService.record(
                actorId, "NOTICE_PUBLISH", AuditTargetType.NOTICE, id.toString(),
                Map.of("published", published));
    }

    @Transactional
    public void delete(UUID actorId, Long id) {
        Notice n = load(id);
        noticeRepository.delete(n);
        auditService.record(actorId, "NOTICE_DELETE", AuditTargetType.NOTICE, id.toString(), Map.of());
    }

    private void validate(String title, String content) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("제목은 비어 있을 수 없습니다");
        }
        if (title.length() > TITLE_MAX) {
            throw new IllegalArgumentException("제목은 " + TITLE_MAX + "자 이하여야 합니다");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("본문은 비어 있을 수 없습니다");
        }
    }

    private Notice load(Long id) {
        return noticeRepository
                .findById(id)
                .orElseThrow(() -> new AdminTargetNotFoundException("공지를 찾을 수 없습니다: " + id));
    }
}

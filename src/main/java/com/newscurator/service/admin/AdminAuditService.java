package com.newscurator.service.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newscurator.domain.AdminAuditLog;
import com.newscurator.domain.enums.AuditTargetType;
import com.newscurator.repository.AdminAuditLogRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 감사 기록(008 FR-060). 변형(부수효과) 행위만 기록.
 *
 * <p>★ {@code record()}는 {@link Propagation#REQUIRED}로 <b>호출자의 트랜잭션에 참여</b>한다
 * (신규 TX·REQUIRES_NEW 아님). 따라서 변형 행위와 감사 기록이 한 트랜잭션 안에 묶여,
 * 행위가 롤백되면 감사도 함께 롤백된다(고아 감사 0). 005 outbox claimer의 REQUIRES_NEW 격리와는
 * 정반대 방향의 의도다.
 */
@Service
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    private final AdminAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AdminAuditService(AdminAuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 변형 행위 감사 기록(호출자 TX 참여). detail은 before/after diff·사유 등.
     *
     * @param actorAccountId 행위자(관리자)
     * @param action 행위 유형(예: ROLE_CHANGE, ARTICLE_HIDE)
     * @param targetType 대상 종류
     * @param targetId 대상 식별자(문자열화, null 허용)
     * @param detail 변경 내용 맵(null 허용 → JSON null 아님, 컬럼 null)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(
            UUID actorAccountId,
            String action,
            AuditTargetType targetType,
            String targetId,
            Map<String, ?> detail) {
        auditLogRepository.save(
                AdminAuditLog.builder()
                        .actorAccountId(actorAccountId)
                        .action(action)
                        .targetType(targetType)
                        .targetId(targetId)
                        .detail(toJson(detail))
                        .build());
        log.debug("[ADMIN-AUDIT] action={}, targetType={}, targetId={}", action, targetType, targetId);
    }

    private String toJson(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            // 감사 detail 직렬화 실패가 행위 자체를 막지 않도록 — detail만 비우고 기록 진행
            log.warn("[ADMIN-AUDIT] detail 직렬화 실패, detail 생략: {}", e.getMessage());
            return null;
        }
    }
}

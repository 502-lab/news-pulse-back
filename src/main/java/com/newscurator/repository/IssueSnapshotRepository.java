package com.newscurator.repository;

import com.newscurator.domain.IssueSnapshot;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IssueSnapshotRepository extends JpaRepository<IssueSnapshot, Long> {

    /** re-derive(OI-4): 매 집계마다 전량 교체. TRUNCATE로 stale 행을 깨끗이 제거. */
    @Modifying
    @Query(value = "TRUNCATE TABLE issue_snapshot RESTART IDENTITY", nativeQuery = true)
    void truncate();

    /** 조회: 증감(delta) 내림차순. NULL은 뒤로. */
    @Query(value = "SELECT * FROM issue_snapshot ORDER BY delta DESC NULLS LAST, id ASC",
            nativeQuery = true)
    List<IssueSnapshot> findAllOrderByDelta();

    /**
     * 보존 정리(FR-009): 90일 경과 스냅샷 삭제. re-derive 모델상 정상 운영 중엔 최신 1세트만 존재하나,
     * 집계 중단·예외로 잔존한 오래된 스냅샷을 방어적으로 정리한다.
     */
    @Modifying
    @Query(value = "DELETE FROM issue_snapshot WHERE derived_at < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}

package com.newscurator.repository;

import com.newscurator.domain.IssueSnapshot;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IssueSnapshotRepository extends JpaRepository<IssueSnapshot, Long> {

    /** re-derive(OI-4): 매 집계마다 전량 교체. TRUNCATE로 stale 행을 깨끗이 제거. */
    @Modifying
    @Query(value = "TRUNCATE TABLE issue_snapshot RESTART IDENTITY", nativeQuery = true)
    void truncate();

    /** 조회: 증감(delta) 내림차순. NULL은 뒤로. */
    @Query(value = "SELECT * FROM issue_snapshot ORDER BY delta DESC NULLS LAST, id ASC",
            nativeQuery = true)
    List<IssueSnapshot> findAllOrderByDelta();
}

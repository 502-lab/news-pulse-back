package com.newscurator.repository;

import com.newscurator.domain.Source;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<Source, Long> {

    List<Source> findByActiveTrue();
}

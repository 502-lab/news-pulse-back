package com.newscurator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "voices")
@Getter
@NoArgsConstructor
public class Voice {

    @Id
    @Column(length = 50)
    private String id; // Naver Clova Voice speaker ID — TODO(V1): NCP 콘솔 확인 후 시드 교체

    @Column(length = 100, nullable = false)
    private String name; // 표시명 ('하린', '준서')

    @Column(length = 10, nullable = false)
    private String gender; // 'FEMALE' | 'MALE' — Gender enum 미존재, String 사용

    @Column(name = "preview_url")
    private String previewUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public Voice(String id, String name, String gender, String previewUrl) {
        this.id = id;
        this.name = name;
        this.gender = gender;
        this.previewUrl = previewUrl;
        this.createdAt = Instant.now();
    }
}

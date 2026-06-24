package com.newscurator.dto.response;

import com.newscurator.domain.Notice;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 공지 응답(008 US4). */
@Schema(description = "공지")
public record NoticeResponse(
        @Schema(description = "식별자") long id,
        @Schema(description = "제목") String title,
        @Schema(description = "본문") String content,
        @Schema(description = "게시 여부") boolean published,
        @Schema(description = "생성 시각") Instant createdAt,
        @Schema(description = "수정 시각") Instant updatedAt) {

    public static NoticeResponse from(Notice n) {
        return new NoticeResponse(
                n.getId(), n.getTitle(), n.getContent(), n.isPublished(),
                n.getCreatedAt(), n.getUpdatedAt());
    }
}

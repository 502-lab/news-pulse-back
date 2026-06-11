package com.newscurator.client.source;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import com.newscurator.domain.Source;
import com.newscurator.domain.enums.SourceAdapterType;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RssSourceAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(RssSourceAdapter.class);

    private final RestClient restClient;

    public RssSourceAdapter(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public boolean supports(SourceAdapterType adapterType) {
        return SourceAdapterType.RSS == adapterType;
    }

    @Override
    public List<ArticleCandidate> fetchCandidates(Source source) {
        try {
            byte[] body =
                    restClient
                            .get()
                            .uri(source.getFeedUrl())
                            .retrieve()
                            .body(byte[].class);

            if (body == null || body.length == 0) {
                return Collections.emptyList();
            }

            try (InputStream is = new ByteArrayInputStream(body)) {
                SyndFeedInput input = new SyndFeedInput();
                SyndFeed feed = input.build(new XmlReader(is));
                return feed.getEntries().stream().map(this::toCandidate).toList();
            }

        } catch (Exception e) {
            log.warn("[RSS] 출처 수집 실패, sourceId={}, url={}: {}",
                    source.getId(), source.getFeedUrl(), e.getMessage());
            throw new RuntimeException("[RSS] 수집 실패: " + source.getFeedUrl(), e);
        }
    }

    private ArticleCandidate toCandidate(SyndEntry entry) {
        String url = entry.getLink() != null ? entry.getLink() : "";
        String title = entry.getTitle() != null ? entry.getTitle() : "";
        String author = entry.getAuthor();
        OffsetDateTime publishedAt = toOffsetDateTime(entry.getPublishedDate());
        String description =
                entry.getDescription() != null ? entry.getDescription().getValue() : null;
        return new ArticleCandidate(url, title, author, publishedAt, description);
    }

    private OffsetDateTime toOffsetDateTime(Date date) {
        if (date == null) return null;
        return date.toInstant().atOffset(ZoneOffset.UTC);
    }
}

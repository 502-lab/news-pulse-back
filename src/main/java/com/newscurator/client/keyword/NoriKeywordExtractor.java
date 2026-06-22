package com.newscurator.client.keyword;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.ko.POS;
import org.apache.lucene.analysis.ko.tokenattributes.PartOfSpeechAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Lucene Nori 기반 한국어 명사 추출기 (NNG 일반명사 / NNP 고유명사만 유지).
 *
 * <p>순수 JVM(네이티브 없음). {@link KoreanTokenizer}는 thread-safe가 아니므로 호출마다 새로 생성한다
 * (배치 추출 빈도상 부담 없음). 커스텀 불용어는 {@code classpath:trend/stopwords-ko.txt}.
 */
@Component
public class NoriKeywordExtractor implements KeywordExtractor {

    private static final int MIN_TERM_LENGTH = 2;
    private final Set<String> stopwords;

    public NoriKeywordExtractor() {
        this.stopwords = loadStopwords();
    }

    @Override
    public Set<String> extractNouns(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        // KoreanTokenizer: discardPunctuation=true 기본, 사전은 nori jar classpath에서 로드
        try (KoreanTokenizer tokenizer = new KoreanTokenizer()) {
            CharTermAttribute term = tokenizer.addAttribute(CharTermAttribute.class);
            PartOfSpeechAttribute pos = tokenizer.addAttribute(PartOfSpeechAttribute.class);
            tokenizer.setReader(new StringReader(text));
            tokenizer.reset();
            while (tokenizer.incrementToken()) {
                POS.Tag tag = pos.getLeftPOS();
                if (tag == POS.Tag.NNG || tag == POS.Tag.NNP) {
                    String t = term.toString();
                    if (t.length() >= MIN_TERM_LENGTH && !stopwords.contains(t)) {
                        result.add(t);
                    }
                }
            }
            tokenizer.end();
        } catch (IOException e) {
            throw new UncheckedIOException("Nori 토큰화 실패", e);
        }
        return result;
    }

    private static Set<String> loadStopwords() {
        Set<String> set = new LinkedHashSet<>();
        try (InputStream in = new ClassPathResource("trend/stopwords-ko.txt").getInputStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String w = line.trim();
                if (!w.isEmpty() && !w.startsWith("#")) {
                    set.add(w);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("불용어 로드 실패", e);
        }
        return set;
    }
}

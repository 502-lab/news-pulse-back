package com.newscurator.client.keyword;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.ko.KoreanTokenizer.DecompoundMode;
import org.apache.lucene.analysis.ko.POS;
import org.apache.lucene.analysis.ko.dict.UserDictionary;
import org.apache.lucene.analysis.ko.tokenattributes.PartOfSpeechAttribute;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.AttributeFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Lucene Nori 기반 한국어 명사 추출기 (NNG 일반명사 / NNP 고유명사만 유지).
 *
 * <p>순수 JVM(네이티브 없음). {@link KoreanTokenizer}는 thread-safe가 아니므로 호출마다 새로 생성한다
 * (배치 추출 빈도상 부담 없음). {@link UserDictionary}는 immutable·thread-safe라 생성자에서 1회 로드해 재사용.
 *
 * <p><b>DecompoundMode.NONE</b>: 복합명사를 통째로 유지한다. 기본값 DISCARD는 {@code 대한민국→[대한,민국]},
 * {@code 월드컵→[월드]}, {@code 백혈병→[백혈]}처럼 트렌드 집계의 근본(동일 개념 count 합산)을 깬다(진단 확인).
 * MIXED는 원형+조각을 모두 내보내 fragment 노이즈를 만들므로 채택하지 않는다.
 *
 * <p>사용자 사전({@code classpath:trend/userdict-ko.txt}): Nori 기본 사전에 없는 OOV 약어·고유명사
 * (예: 연준→연주, 홍명보→보호, 남아공→남아 오분절)를 교정. 불용어는 {@code classpath:trend/stopwords-ko.txt}.
 *
 * <p><b>재추출 주의</b>: 추출 규칙(DecompoundMode/사전) 변경 후, 이미 추출된 환경에서는 기존 키워드가
 * 깨진 채 남는다(article_keyword PK 멱등은 같은 term만 막음). 배포 환경에서 이미 추출됐다면
 * {@code TRUNCATE article_keyword} 후 재집계가 필요하다. (dev는 V14 미적용 → corpus 비어있어 불필요.)
 */
@Component
public class NoriKeywordExtractor implements KeywordExtractor {

    private static final int MIN_TERM_LENGTH = 2;

    private final Set<String> stopwords;
    private final UserDictionary userDictionary;

    public NoriKeywordExtractor() {
        this.stopwords = loadStopwords();
        this.userDictionary = loadUserDictionary();
    }

    @Override
    public Set<String> extractNouns(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        // DecompoundMode.NONE: 복합명사 통째 유지. discardPunctuation=true, outputUnknownUnigrams=false.
        try (KoreanTokenizer tokenizer = new KoreanTokenizer(
                AttributeFactory.DEFAULT_ATTRIBUTE_FACTORY,
                userDictionary,
                DecompoundMode.NONE,
                false,
                true)) {
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

    /**
     * 사용자 사전 로드. UserDictionary.open은 # 주석·빈 줄을 무시한다.
     * 유효 표제어가 0개면 null(KoreanTokenizer는 null 사전 허용).
     */
    private static UserDictionary loadUserDictionary() {
        try (InputStream in = new ClassPathResource("trend/userdict-ko.txt").getInputStream();
                Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return UserDictionary.open(reader);
        } catch (IllegalArgumentException emptyDict) {
            return null; // 유효 표제어 없음
        } catch (IOException e) {
            throw new UncheckedIOException("사용자 사전 로드 실패", e);
        }
    }
}

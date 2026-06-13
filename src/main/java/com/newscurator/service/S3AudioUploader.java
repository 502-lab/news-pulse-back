package com.newscurator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 MP3 파일 업로드 + CloudFront URL 생성.
 *
 * <p>DB에는 audio_key(S3 키)만 저장하고, presigned URL은 저장하지 않는다.
 * URL은 응답 시점에 CloudFront 도메인과 audio_key를 조합하여 생성한다.
 */
@Service
public class S3AudioUploader {

    private static final Logger log = LoggerFactory.getLogger(S3AudioUploader.class);

    private final S3Client s3Client;
    private final String bucket;
    private final String cloudfrontDomain;

    public S3AudioUploader(
            S3Client s3Client,
            @Value("${cloud.aws.s3.bucket}") String bucket,
            @Value("${cloud.aws.cloudfront.domain}") String cloudfrontDomain) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.cloudfrontDomain = cloudfrontDomain;
    }

    /**
     * MP3 bytes를 S3에 업로드하고 audio_key를 반환한다.
     *
     * @param mp3Bytes  MP3 바이너리
     * @param audioKey  S3 키 (예: tts/article/12345/harin.mp3)
     * @return 저장된 audioKey (입력과 동일)
     */
    public String upload(byte[] mp3Bytes, String audioKey) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(audioKey)
                .contentType("audio/mpeg")
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(mp3Bytes));
        log.info("TTS audio uploaded: key={}", audioKey);
        return audioKey;
    }

    /**
     * audio_key로 CloudFront 공개 URL을 생성한다. presigned URL 아님.
     *
     * @param audioKey S3 키
     * @return CloudFront 영구 공개 URL
     */
    public String generateUrl(String audioKey) {
        return cloudfrontDomain + "/" + audioKey;
    }
}

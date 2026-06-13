package com.newscurator.client.ai;

/** TTS 오디오 생성 제공자 인터페이스. */
public interface TtsProvider {
    byte[] generate(String voiceId, String text);
}

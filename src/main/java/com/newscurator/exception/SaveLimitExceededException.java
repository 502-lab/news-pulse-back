package com.newscurator.exception;

public class SaveLimitExceededException extends RuntimeException {

    public SaveLimitExceededException() {
        super("저장 기사는 최대 1,000건까지 가능합니다");
    }
}

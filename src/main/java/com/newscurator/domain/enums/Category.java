package com.newscurator.domain.enums;

public enum Category {
    ECONOMY_FINANCE("경제·금융"),
    SCIENCE("과학"),
    POLITICS("정치"),
    SPORTS("스포츠"),
    WORLD("세계"),
    ENTERTAINMENT_CULTURE("연예·문화"),
    HEALTH_MEDICINE("건강·의학"),
    AUTOMOTIVE("자동차"),
    IT("IT"),
    OTHER("기타");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}

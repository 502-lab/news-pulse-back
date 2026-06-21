package com.newscurator.domain;

import com.newscurator.domain.enums.EmailSubscriptionType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EmailSubscriptionId implements Serializable {

    @Column(name = "account_id", columnDefinition = "uuid")
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private EmailSubscriptionType type;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmailSubscriptionId other)) return false;
        return Objects.equals(accountId, other.accountId) && Objects.equals(type, other.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, type);
    }
}

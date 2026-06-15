package com.newscurator.client.social;

import com.newscurator.domain.enums.SocialProvider;
import com.newscurator.exception.InvalidProviderException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class OAuthProviderFactory {

    private final Map<SocialProvider, OAuthProviderPort> providers;

    public OAuthProviderFactory(List<OAuthProviderPort> providerList) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(OAuthProviderPort::getProvider, Function.identity()));
    }

    public OAuthProviderPort get(SocialProvider provider) {
        OAuthProviderPort port = providers.get(provider);
        if (port == null) {
            throw new InvalidProviderException("Unknown OAuth provider: " + provider);
        }
        return port;
    }
}

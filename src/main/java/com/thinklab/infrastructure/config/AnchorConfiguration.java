package com.thinklab.infrastructure.config;

import com.thinklab.domain.model.AnchorSigner;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires the domain's {@link AnchorSigner} to {@code ledger.anchor.key} / {@code key-id} / {@code previous-keys} (ADR-035). Without a
 * key it can authenticate and sign nothing.
 */
@Factory
public class AnchorConfiguration {

    @Singleton
    public AnchorSigner anchorSigner(AnchorProperties properties) {
        Map<String, String> retired = new LinkedHashMap<>(properties.getPreviousKeys());
        for (String pair : properties.getPreviousKeysList().split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                retired.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
        }
        return new AnchorSigner(properties.getKeyId(), properties.getKey(), retired);
    }
}

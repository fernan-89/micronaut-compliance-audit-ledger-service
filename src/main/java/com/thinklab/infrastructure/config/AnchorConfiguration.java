package com.thinklab.infrastructure.config;

import com.thinklab.domain.model.AnchorSigner;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/** Wires the domain's {@link AnchorSigner} to {@code ledger.anchor.key}. Without a key it can authenticate and sign nothing. */
@Factory
public class AnchorConfiguration {

    @Singleton
    public AnchorSigner anchorSigner(AnchorProperties properties) {
        return new AnchorSigner(properties.getKey());
    }
}

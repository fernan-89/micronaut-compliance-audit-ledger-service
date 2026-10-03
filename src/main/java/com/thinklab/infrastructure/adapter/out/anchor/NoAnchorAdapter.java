package com.thinklab.infrastructure.adapter.out.anchor;

import com.thinklab.domain.exception.AnchoringNotConfiguredException;
import com.thinklab.domain.model.Anchor;
import com.thinklab.domain.port.AnchorPort;
import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** The anchor port of a deployment that has not configured anchoring: there are no anchors, and none can be published. */
@Singleton
@Secondary
public class NoAnchorAdapter implements AnchorPort {

    @Override
    public Mono<Void> publish(Anchor anchor) {
        return Mono.error(new AnchoringNotConfiguredException());
    }

    @Override
    public Flux<Anchor> read(UUID organisationId) {
        return Flux.empty();
    }
}

package com.thinklab.infrastructure.adapter.out.anchor;

import com.thinklab.domain.model.Anchor;
import com.thinklab.domain.port.AnchorPort;
import com.thinklab.infrastructure.config.AnchorProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Publishes anchors by appending one JSON line per anchor to {@code <directory>/<organisationId>.jsonl}, with the write forced to
 * disk. The directory is meant to be a write-once volume (or a mount of an object store with object lock): the file adapter is the
 * simplest destination that satisfies "outside the ledger's database", and the HMAC on each line means an editable volume is still
 * tamper-evident. Blocking file I/O runs on the bounded-elastic scheduler, off the event loop.
 */
@Singleton
@Requires(property = "ledger.anchor.enabled", value = "true")
@Requires(property = "ledger.anchor.sink", notEquals = "s3")
public class FileAnchorAdapter implements AnchorPort {

    private final Path directory;
    private final JsonMapper json;

    public FileAnchorAdapter(AnchorProperties properties, JsonMapper json) {
        if (properties.getKey() == null || properties.getKey().isBlank()) {
            throw new IllegalStateException("ledger.anchor.key is required when ledger.anchor.enabled is true: unsigned anchors prove nothing.");
        }
        this.directory = Path.of(properties.getDirectory());
        this.json = json;
    }

    @Override
    public Mono<Void> publish(Anchor anchor) {
        return Mono.<Void>fromRunnable(() -> append(anchor)).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<Anchor> read(UUID organisationId) {
        return Flux.defer(() -> {
            Path file = fileOf(organisationId);
            if (!Files.exists(file)) {
                return Flux.<Anchor>empty();
            }
            return Flux.fromIterable(readLines(file)).filter(line -> !line.isBlank()).map(this::parse);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** One call that fails the same way on every OS (Files.lines only fails on open on some platforms, later on others). */
    private static java.util.List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the anchor store: " + e.getMessage(), e);
        }
    }

    private void append(Anchor anchor) {
        try {
            Files.createDirectories(directory);
            String line = json.writeValueAsString(AnchorLine.of(anchor)) + System.lineSeparator();
            Files.writeString(fileOf(anchor.organisationId()), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not publish the anchor: " + e.getMessage(), e);
        }
    }

    private Anchor parse(String text) {
        try {
            return json.readValue(text, AnchorLine.class).toAnchor();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("The anchor store holds a line that cannot be read: it was corrupted or tampered with.", e);
        }
    }

    private Path fileOf(UUID organisationId) {
        return directory.resolve(organisationId + ".jsonl");
    }
}

package com.thinklab.infrastructure.adapter.out.anchor;

import com.thinklab.application.dto.response.AnchorResultResponse;
import com.thinklab.application.usecase.AnchorChainHeadsUseCase;
import com.thinklab.domain.exception.AnchoringNotConfiguredException;
import com.thinklab.domain.model.Anchor;
import com.thinklab.domain.model.AnchorSigner;
import com.thinklab.infrastructure.adapter.in.AnchorScheduler;
import com.thinklab.infrastructure.config.AnchorConfiguration;
import com.thinklab.infrastructure.config.AnchorProperties;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnchorAdaptersTest {

    private static final Instant WHEN = Instant.parse("2026-10-03T12:00:00.123Z");
    private final JsonMapper json = JsonMapper.createDefault();
    private final AnchorSigner signer = new AnchorSigner("anchor-key");

    private AnchorProperties properties(Path directory, String key) {
        AnchorProperties properties = new AnchorProperties();
        properties.setEnabled(true);
        properties.setDirectory(directory.toString());
        properties.setKey(key);
        return properties;
    }

    // ------------------------------------------------------------ file adapter

    @Test
    @DisplayName("anchors are appended one JSON line each to <directory>/<organisation>.jsonl and read back oldest first, per tenant")
    void publishesAndReadsBack(@TempDir Path directory) throws IOException {
        FileAnchorAdapter adapter = new FileAnchorAdapter(properties(directory.resolve("nested/anchors"), "anchor-key"), json);
        UUID org = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Anchor first = signer.sign(org, 3, "ab".repeat(32), WHEN);
        Anchor second = signer.sign(org, 7, "cd".repeat(32), WHEN.plusSeconds(60));

        StepVerifier.create(adapter.publish(first)).verifyComplete();
        StepVerifier.create(adapter.publish(second)).verifyComplete();

        assertEquals(List.of(first, second), adapter.read(org).collectList().block());
        assertEquals(List.of(), adapter.read(other).collectList().block());
        List<String> lines = Files.readAllLines(directory.resolve("nested/anchors/" + org + ".jsonl"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"headSequence\":3"));
        assertFalse(Files.exists(directory.resolve("nested/anchors/" + other + ".jsonl")));
    }

    @Test
    @DisplayName("an edited anchor line still reads, but no longer authenticates; a blank line is skipped")
    void editedLinesAreVisibleToTheSigner(@TempDir Path directory) throws IOException {
        FileAnchorAdapter adapter = new FileAnchorAdapter(properties(directory, "anchor-key"), json);
        UUID org = UUID.randomUUID();
        adapter.publish(signer.sign(org, 3, "ab".repeat(32), WHEN)).block();
        Path file = directory.resolve(org + ".jsonl");
        Files.writeString(file, Files.readString(file).replace("\"headSequence\":3", "\"headSequence\":9") + System.lineSeparator(), StandardCharsets.UTF_8);

        List<Anchor> read = adapter.read(org).collectList().block();

        assertEquals(1, read.size());
        assertEquals(9, read.get(0).headSequence());
        assertFalse(signer.isAuthentic(read.get(0)));
    }

    @Test
    @DisplayName("a corrupted line is reported as a tampered anchor store, not silently skipped")
    void corruptedLine(@TempDir Path directory) throws IOException {
        FileAnchorAdapter adapter = new FileAnchorAdapter(properties(directory, "anchor-key"), json);
        UUID org = UUID.randomUUID();
        Files.writeString(directory.resolve(org + ".jsonl"), "this is not json" + System.lineSeparator());

        StepVerifier.create(adapter.read(org)).expectErrorSatisfies(e -> {
            assertTrue(e instanceof IllegalStateException);
            assertTrue(e.getMessage().contains("tampered"));
        }).verify();
    }

    @Test
    @DisplayName("a store that cannot be written or read surfaces as an I/O error")
    void unwritableStore(@TempDir Path directory) throws IOException {
        Path notADirectory = Files.writeString(directory.resolve("file"), "x");
        FileAnchorAdapter adapter = new FileAnchorAdapter(properties(notADirectory, "anchor-key"), json);
        UUID org = UUID.randomUUID();

        StepVerifier.create(adapter.publish(signer.sign(org, 1, "ab".repeat(32), WHEN))).expectError(java.io.UncheckedIOException.class).verify();

        Path unreadable = Files.createDirectory(directory.resolve("anchors"));
        Files.createDirectory(unreadable.resolve(org + ".jsonl"));
        StepVerifier.create(new FileAnchorAdapter(properties(unreadable, "anchor-key"), json).read(org)).expectError(java.io.UncheckedIOException.class).verify();
    }

    @Test
    @DisplayName("the file adapter refuses to start without a signing key")
    void requiresAKey(@TempDir Path directory) {
        assertThrows(IllegalStateException.class, () -> new FileAnchorAdapter(properties(directory, ""), json));
        assertThrows(IllegalStateException.class, () -> new FileAnchorAdapter(properties(directory, "   "), json));
        assertThrows(IllegalStateException.class, () -> new FileAnchorAdapter(properties(directory, null), json));
    }

    // ------------------------------------------------------------ no-op adapter

    @Test
    @DisplayName("without anchoring there are no anchors and publishing is refused with AnchoringNotConfiguredException")
    void noAnchorAdapter() {
        NoAnchorAdapter adapter = new NoAnchorAdapter();

        assertEquals(List.of(), adapter.read(UUID.randomUUID()).collectList().block());
        StepVerifier.create(adapter.publish(signer.sign(UUID.randomUUID(), 1, "ab".repeat(32), WHEN))).expectError(AnchoringNotConfiguredException.class).verify();
        assertEquals("ERR-LED-00503", new AnchoringNotConfiguredException().getErrorCode());
    }

    // ------------------------------------------------------------ configuration and scheduler

    @Test
    @DisplayName("properties default to disabled with a local directory and no key; the signer follows the key")
    void propertiesAndSigner() {
        AnchorProperties defaults = new AnchorProperties();
        assertFalse(defaults.isEnabled());
        assertEquals("./anchors", defaults.getDirectory());
        assertEquals("", defaults.getKey());

        AnchorConfiguration configuration = new AnchorConfiguration();
        assertFalse(configuration.anchorSigner(defaults).hasKey());
        defaults.setKey("k");
        assertTrue(configuration.anchorSigner(defaults).hasKey());
    }

    @Test
    @DisplayName("the scheduler runs one anchoring pass over every tenant")
    void schedulerRunsAPass() {
        AnchorChainHeadsUseCase useCase = mock(AnchorChainHeadsUseCase.class);
        when(useCase.executeAll()).thenReturn(Flux.just(new AnchorResultResponse("UNCHANGED", null, null)));

        new AnchorScheduler(useCase).run();

        verify(useCase).executeAll();
    }

    @Test
    @DisplayName("a failing pass is logged, never thrown into the scheduler")
    void schedulerSurvivesAFailingPass() {
        AnchorChainHeadsUseCase useCase = mock(AnchorChainHeadsUseCase.class);
        when(useCase.executeAll()).thenReturn(Flux.error(new IllegalStateException("boom")));

        new AnchorScheduler(useCase).run();

        verify(useCase).executeAll();
    }
}

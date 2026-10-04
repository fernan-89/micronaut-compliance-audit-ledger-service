package com.thinklab.infrastructure.adapter.out.anchor;

import com.thinklab.domain.model.Anchor;
import com.thinklab.domain.model.AnchorSigner;
import com.thinklab.domain.port.AnchorPort;
import com.thinklab.infrastructure.adapter.out.persistence.MongoContainerAccess;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The object-lock anchor store against an S3-compatible server (LocalStack, which enforces Object Lock like S3 does), ADR-035: anchors written and read back in order, signed
 * under a named key, and - the point of the store - NOT removable: deleting a locked version is refused, and a plain delete (which
 * on a versioned bucket only adds a delete marker that hides the object from a normal listing) does not hide the anchors from the
 * ledger.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3AnchorStoreIT implements TestPropertyProvider {

    private static final String BUCKET = "thinklab-anchors-it";
    private static final String KEY = "it-anchor-key";
    private static final GenericContainer<?> STORE = new GenericContainer<>(DockerImageName.parse("localstack/localstack:3.8.1"))
            .withEnv("SERVICES", "s3")
            .withExposedPorts(4566)
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forResponsePredicate(body -> body.contains("\"s3\": \"available\"") || body.contains("\"s3\": \"running\"")));

    @Override
    public Map<String, String> getProperties() {
        STORE.start();
        return Map.ofEntries(
                Map.entry("mongodb.uri", MongoContainerAccess.uri("ledger_anchor_s3_it")),
                Map.entry("ledger.anchor.enabled", "true"),
                Map.entry("ledger.anchor.sink", "s3"),
                Map.entry("ledger.anchor.key", KEY),
                Map.entry("ledger.anchor.s3.endpoint", "http://" + STORE.getHost() + ":" + STORE.getMappedPort(4566)),
                Map.entry("ledger.anchor.s3.path-style", "true"),
                Map.entry("ledger.anchor.s3.access-key", "test"),
                Map.entry("ledger.anchor.s3.secret-key", "test"),
                Map.entry("ledger.anchor.s3.bucket", BUCKET),
                Map.entry("thinklab.mongo.create-indexes", "false"));
    }

    @Inject
    AnchorPort anchors;

    @Inject
    S3Client s3;

    private final AnchorSigner signer = new AnchorSigner("k1", KEY, Map.of());
    private boolean bucketReady;

    private void bucket() {
        if (!bucketReady) {
            // Object Lock can only be switched on when the bucket is created.
            s3.createBucket(request -> request.bucket(BUCKET).objectLockEnabledForBucket(true));
            bucketReady = true;
        }
    }

    private List<ObjectVersion> versionsOf(UUID org) {
        return s3.listObjectVersions(ListObjectVersionsRequest.builder().bucket(BUCKET).prefix("anchors/" + org + "/").build()).versions();
    }

    @Test
    @DisplayName("anchors are written as locked objects and read back oldest first, still authentic under their named key")
    void writesAndReadsBack() {
        bucket();
        UUID org = UUID.randomUUID();
        Anchor first = signer.sign(org, 3, "ab".repeat(32), Instant.parse("2026-10-03T12:00:00.123Z"));
        Anchor second = signer.sign(org, 12, "cd".repeat(32), Instant.parse("2026-10-03T13:00:00.456Z"));

        anchors.publish(second).block();
        anchors.publish(first).block();

        List<Anchor> read = anchors.read(org).collectList().block();
        assertEquals(List.of(first, second), read);
        assertTrue(read.stream().allMatch(signer::isAuthentic));
        assertEquals("k1", read.get(0).keyId());
        assertTrue(anchors.read(UUID.randomUUID()).collectList().block().isEmpty());
        var retention = s3.getObjectRetention(request -> request.bucket(BUCKET).key(versionsOf(org).get(0).key()).versionId(versionsOf(org).get(0).versionId()));
        assertEquals(ObjectLockRetentionMode.COMPLIANCE, retention.retention().mode());
    }

    @Test
    @DisplayName("deleting a locked version is refused, so an anchor cannot be removed")
    void lockedVersionCannotBeDeleted() {
        bucket();
        UUID org = UUID.randomUUID();
        anchors.publish(signer.sign(org, 5, "ab".repeat(32), Instant.now())).block();
        ObjectVersion version = versionsOf(org).get(0);

        assertThrows(S3Exception.class, () -> s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(version.key()).versionId(version.versionId()).build()));

        assertEquals(1, anchors.read(org).collectList().block().size());
    }

    @Test
    @DisplayName("a plain delete only hides the object from a normal listing; the ledger still reads the locked anchor")
    void plainDeleteDoesNotHideAnchors() {
        bucket();
        UUID org = UUID.randomUUID();
        Anchor anchor = signer.sign(org, 5, "ab".repeat(32), Instant.now());
        anchors.publish(anchor).block();
        String key = versionsOf(org).get(0).key();

        s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).build());

        // the hazard: an ordinary listing no longer shows the anchor...
        assertEquals(0, s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix("anchors/" + org + "/").build()).contents().size());
        // ...and reading by version, as the adapter does, is not fooled
        assertEquals(List.of(anchor), anchors.read(org).collectList().block());
    }
}

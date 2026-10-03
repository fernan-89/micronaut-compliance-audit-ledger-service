package com.thinklab.infrastructure.config;

import com.thinklab.domain.model.AnchorSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Anchor configuration (ADR-034, ADR-035): defaults, the signer built from it, and the object-store client. */
class AnchorConfigurationTest {

    @Test
    @DisplayName("the defaults: anchoring off, the file store, key k1, no retired keys; the object store defaults to COMPLIANCE for ten years")
    void defaults() {
        AnchorProperties anchor = new AnchorProperties();
        AnchorS3Properties s3 = new AnchorS3Properties();

        assertFalse(anchor.isEnabled());
        assertEquals("file", anchor.getSink());
        anchor.setSink("s3");
        assertEquals("s3", anchor.getSink());
        assertEquals("./anchors", anchor.getDirectory());
        assertEquals("", anchor.getKey());
        assertEquals("k1", anchor.getKeyId());
        assertTrue(anchor.getPreviousKeys().isEmpty());
        assertEquals("COMPLIANCE", s3.getMode());
        assertEquals(3650, s3.getRetentionDays());
        assertEquals("anchors/", s3.getPrefix());
        assertEquals("us-east-1", s3.getRegion());
        assertFalse(s3.isPathStyle());
    }

    @Test
    @DisplayName("the signer is built from the current key, its id and the retired keys")
    void signerFromProperties() {
        AnchorProperties anchor = new AnchorProperties();
        anchor.setKey("second-key");
        anchor.setKeyId("k2");
        anchor.setPreviousKeys(Map.of("k1", "first-key"));
        anchor.setPreviousKeysList("k0=oldest-key, junk ,=nokey, k9 = spaced-key");
        AnchorSigner signer = new AnchorConfiguration().anchorSigner(anchor);
        var retired = new AnchorSigner("k1", "first-key", Map.of()).sign(UUID.randomUUID(), 1, "ab".repeat(32), java.time.Instant.now());

        assertEquals("k2", signer.keyId());
        assertTrue(signer.isAuthentic(retired));
        var oldest = new AnchorSigner("k0", "oldest-key", Map.of()).sign(UUID.randomUUID(), 1, "ab".repeat(32), java.time.Instant.now());
        var spaced = new AnchorSigner("k9", "spaced-key", Map.of()).sign(UUID.randomUUID(), 1, "ab".repeat(32), java.time.Instant.now());
        assertTrue(signer.isAuthentic(oldest));
        assertTrue(signer.isAuthentic(spaced));
        assertEquals("", new AnchorProperties().getPreviousKeysList());
    }

    @Test
    @DisplayName("the object-store client is built for a custom endpoint with static keys, and for the default credential chain without one")
    void s3Client() {
        AnchorS3Properties minio = new AnchorS3Properties();
        minio.setEndpoint("http://localhost:9000");
        minio.setPathStyle(true);
        minio.setAccessKey("minio");
        minio.setSecretKey("minio-secret");
        minio.setRegion("eu-west-1");
        minio.setBucket("b");
        minio.setPrefix("p/");
        minio.setRetentionDays(30);
        minio.setMode("GOVERNANCE");
        AnchorS3Properties aws = new AnchorS3Properties();

        try (S3Client custom = new AnchorS3ClientFactory().s3Client(minio); S3Client standard = new AnchorS3ClientFactory().s3Client(aws)) {
            assertNotNull(custom);
            assertNotNull(standard);
        }
        assertEquals("http://localhost:9000", minio.getEndpoint());
        assertEquals("minio", minio.getAccessKey());
        assertEquals("minio-secret", minio.getSecretKey());
        assertEquals("b", minio.getBucket());
        assertTrue(minio.isPathStyle());
    }
}

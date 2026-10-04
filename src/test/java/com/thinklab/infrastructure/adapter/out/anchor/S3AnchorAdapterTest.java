package com.thinklab.infrastructure.adapter.out.anchor;

import com.thinklab.domain.model.Anchor;
import com.thinklab.domain.model.AnchorSigner;
import com.thinklab.infrastructure.config.AnchorProperties;
import com.thinklab.infrastructure.config.AnchorS3Properties;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The object-lock anchor store (ADR-035), against a mocked S3 client: what is written, how it is locked, and how it is read back. */
class S3AnchorAdapterTest {

    private static final Instant WHEN = Instant.parse("2026-10-03T12:00:00.123Z");
    private final JsonMapper json = JsonMapper.createDefault();
    private final AnchorSigner signer = new AnchorSigner("k1", "anchor-key", java.util.Map.of());
    private final S3Client s3 = mock(S3Client.class);
    private final AnchorProperties anchor = new AnchorProperties();
    private final AnchorS3Properties properties = new AnchorS3Properties();
    private S3AnchorAdapter adapter;

    @BeforeEach
    void setUp() {
        anchor.setEnabled(true);
        anchor.setKey("anchor-key");
        properties.setBucket("thinklab-anchors");
        adapter = new S3AnchorAdapter(s3, anchor, properties, json);
    }

    private ResponseBytes<GetObjectResponse> bytesOf(Anchor a) throws Exception {
        return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), json.writeValueAsString(AnchorLine.of(a)).getBytes(StandardCharsets.UTF_8));
    }

    private static ObjectVersion version(String key, String id) {
        return ObjectVersion.builder().key(key).versionId(id).build();
    }

    // ------------------------------------------------------------ publish

    @Test
    @DisplayName("an anchor is one object under <prefix><organisation>/, locked in COMPLIANCE mode until its retention ends, with a checksum")
    void publishesLockedObject() throws Exception {
        UUID org = UUID.randomUUID();
        Anchor published = signer.sign(org, 7, "cd".repeat(32), WHEN);
        Instant before = Instant.now();

        StepVerifier.create(adapter.publish(published)).verifyComplete();

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(request.capture(), body.capture());
        assertEquals("thinklab-anchors", request.getValue().bucket());
        assertEquals("anchors/" + org + "/00000000000000000007-" + String.format("%013d", WHEN.toEpochMilli()) + ".json", request.getValue().key());
        assertEquals(ObjectLockMode.COMPLIANCE, request.getValue().objectLockMode());
        assertTrue(request.getValue().objectLockRetainUntilDate().isAfter(before.plusSeconds(3649L * 86400)));
        assertEquals(24, request.getValue().contentMD5().length());
        assertEquals(S3AnchorAdapter.digestBase64("MD5", new String(body.getValue().contentStreamProvider().newStream().readAllBytes(), StandardCharsets.UTF_8)), request.getValue().contentMD5());
        assertEquals("application/json", request.getValue().contentType());
        assertTrue(body.getValue().contentLength() > 0);
    }

    @Test
    @DisplayName("GOVERNANCE mode and a shorter retention are honoured, and the mode is not case sensitive")
    void governanceMode() {
        properties.setMode("governance");
        properties.setRetentionDays(1);
        S3AnchorAdapter governance = new S3AnchorAdapter(s3, anchor, properties, json);

        governance.publish(signer.sign(UUID.randomUUID(), 1, "ab".repeat(32), WHEN)).block();

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertEquals(ObjectLockMode.GOVERNANCE, request.getValue().objectLockMode());
        assertTrue(request.getValue().objectLockRetainUntilDate().isBefore(Instant.now().plusSeconds(2 * 86400)));
    }

    @Test
    @DisplayName("a store that refuses the write fails the publish (the anchor is not silently lost)")
    void failedPublishFails() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(S3Exception.builder().message("denied").build());

        StepVerifier.create(adapter.publish(signer.sign(UUID.randomUUID(), 1, "ab".repeat(32), WHEN))).expectError(S3Exception.class).verify();
    }

    // ------------------------------------------------------------ read

    @Test
    @DisplayName("anchors are read from every object VERSION, across pages, and returned oldest first")
    void readsEveryVersionOldestFirst() throws Exception {
        UUID org = UUID.randomUUID();
        Anchor first = signer.sign(org, 3, "ab".repeat(32), WHEN);
        Anchor second = signer.sign(org, 7, "cd".repeat(32), WHEN.plusSeconds(60));
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().versions(version("anchors/" + org + "/b", "v2")).isTruncated(true)
                        .nextKeyMarker("anchors/" + org + "/b").nextVersionIdMarker("v2").build())
                .thenReturn(ListObjectVersionsResponse.builder().versions(version("anchors/" + org + "/a", "v1")).isTruncated(false).build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(bytesOf(second)).thenReturn(bytesOf(first));

        StepVerifier.create(adapter.read(org).collectList()).expectNext(List.of(first, second)).verifyComplete();

        ArgumentCaptor<ListObjectVersionsRequest> list = ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        verify(s3, times(2)).listObjectVersions(list.capture());
        assertEquals("anchors/" + org + "/", list.getAllValues().get(0).prefix());
        assertEquals("anchors/" + org + "/b", list.getAllValues().get(1).keyMarker());
        assertEquals("v2", list.getAllValues().get(1).versionIdMarker());
        ArgumentCaptor<GetObjectRequest> get = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3, times(2)).getObjectAsBytes(get.capture());
        assertEquals("v2", get.getAllValues().get(0).versionId());
    }

    @Test
    @DisplayName("a delete marker hides nothing: the locked version is still read, and the attempt is logged")
    void deleteMarkersAreIgnoredForReading() throws Exception {
        UUID org = UUID.randomUUID();
        Anchor only = signer.sign(org, 3, "ab".repeat(32), WHEN);
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().versions(version("anchors/" + org + "/a", "v1"))
                        .deleteMarkers(DeleteMarkerEntry.builder().key("anchors/" + org + "/a").versionId("dm").build()).build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(bytesOf(only));

        StepVerifier.create(adapter.read(org).collectList()).expectNext(List.of(only)).verifyComplete();
    }

    @Test
    @DisplayName("a tenant with no anchors reads as empty")
    void emptyWhenNoAnchors() {
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(ListObjectVersionsResponse.builder().build());

        StepVerifier.create(adapter.read(UUID.randomUUID())).verifyComplete();
    }

    @Test
    @DisplayName("an object that cannot be read as an anchor means the store was corrupted or tampered with")
    void corruptedObject() {
        UUID org = UUID.randomUUID();
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder().versions(version("anchors/" + org + "/a", "v1")).build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), "not json".getBytes(StandardCharsets.UTF_8)));

        StepVerifier.create(adapter.read(org)).expectErrorSatisfies(error -> assertTrue(error.getMessage().contains("tampered"))).verify();
    }

    // ------------------------------------------------------------ configuration

    @Test
    @DisplayName("without a signing key, a bucket, or with an unknown lock mode the store refuses to start")
    void refusesBadConfiguration() {
        AnchorProperties keyless = new AnchorProperties();
        assertThrows(IllegalStateException.class, () -> new S3AnchorAdapter(s3, keyless, properties, json));
        keyless.setKey(" ");
        assertThrows(IllegalStateException.class, () -> new S3AnchorAdapter(s3, keyless, properties, json));

        AnchorS3Properties noBucket = new AnchorS3Properties();
        assertThrows(IllegalStateException.class, () -> new S3AnchorAdapter(s3, anchor, noBucket, json));
        noBucket.setBucket("  ");
        assertThrows(IllegalStateException.class, () -> new S3AnchorAdapter(s3, anchor, noBucket, json));

        properties.setMode("WORM");
        assertThrows(IllegalStateException.class, () -> new S3AnchorAdapter(s3, anchor, properties, json));
    }
    @Test
    @DisplayName("the Content-MD5 digest reports an unavailable algorithm as an IllegalStateException")
    void digestFailure() {
        assertThrows(IllegalStateException.class, () -> S3AnchorAdapter.digestBase64("NOT-AN-ALGORITHM", "x"));
    }

    @Test
    @DisplayName("an anchor that cannot be serialised is not published (the failure is reported, not swallowed)")
    void unserialisableAnchor() throws Exception {
        JsonMapper broken = mock(JsonMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new java.io.IOException("no"));
        S3AnchorAdapter failing = new S3AnchorAdapter(s3, anchor, properties, broken);

        StepVerifier.create(failing.publish(signer.sign(UUID.randomUUID(), 1, "ab".repeat(32), WHEN))).expectError(java.io.UncheckedIOException.class).verify();
        verify(s3, times(0)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}

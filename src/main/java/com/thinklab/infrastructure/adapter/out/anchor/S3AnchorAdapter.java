package com.thinklab.infrastructure.adapter.out.anchor;

import com.thinklab.domain.model.Anchor;
import com.thinklab.domain.port.AnchorPort;
import com.thinklab.infrastructure.config.AnchorProperties;
import com.thinklab.infrastructure.config.AnchorS3Properties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Publishes each anchor as its own object in an S3-compatible bucket with <b>Object Lock</b> (ADR-035): once written, an anchor
 * cannot be overwritten or deleted until its retention ends - in COMPLIANCE mode not even by the account that wrote it - so, unlike
 * the file store, the destination itself enforces "write once" instead of relying on how a volume is mounted. The HMAC on each
 * anchor still protects against a store that is wrongly configured.
 *
 * <p>One object per anchor, named {@code <prefix><organisation>/<sequence, zero padded>-<millis>.json}, so a listing reads oldest
 * first. Reading goes through the object VERSIONS, not the plain listing: on a versioned bucket a plain delete only adds a delete
 * marker that hides the object from an ordinary listing, and an anchor store that silently lost its anchors would look as if
 * nothing had been anchored. Delete markers are ignored for reading (the locked version is still there) and logged at error level,
 * because someone tried to remove an anchor.
 */
@Singleton
@Requires(property = "ledger.anchor.enabled", value = "true")
@Requires(property = "ledger.anchor.sink", value = "s3")
public class S3AnchorAdapter implements AnchorPort {

    private static final Logger log = LoggerFactory.getLogger(S3AnchorAdapter.class);

    private final S3Client s3;
    private final AnchorS3Properties properties;
    private final JsonMapper json;
    private final ObjectLockMode mode;

    public S3AnchorAdapter(S3Client s3, AnchorProperties anchor, AnchorS3Properties properties, JsonMapper json) {
        if (anchor.getKey().isBlank()) {
            throw new IllegalStateException("ledger.anchor.key is required when ledger.anchor.enabled is true: unsigned anchors prove nothing.");
        }
        if (properties.getBucket().isBlank()) {
            throw new IllegalStateException("ledger.anchor.s3.bucket is required when ledger.anchor.sink is s3.");
        }
        this.mode = switch (properties.getMode().toUpperCase()) {
            case "COMPLIANCE" -> ObjectLockMode.COMPLIANCE;
            case "GOVERNANCE" -> ObjectLockMode.GOVERNANCE;
            default -> throw new IllegalStateException("ledger.anchor.s3.mode must be COMPLIANCE or GOVERNANCE.");
        };
        this.s3 = s3;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public Mono<Void> publish(Anchor anchor) {
        return Mono.<Void>fromRunnable(() -> put(anchor)).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<Anchor> read(UUID organisationId) {
        return Flux.defer(() -> Flux.fromIterable(readAll(organisationId))).subscribeOn(Schedulers.boundedElastic());
    }

    private void put(Anchor anchor) {
        String body;
        try {
            body = json.writeValueAsString(AnchorLine.of(anchor));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not publish the anchor: " + e.getMessage(), e);
        }
        String key = properties.getPrefix() + anchor.organisationId() + "/" + String.format("%020d-%013d.json", anchor.headSequence(), anchor.anchoredAt().toEpochMilli());
        s3.putObject(PutObjectRequest.builder()
                        .bucket(properties.getBucket())
                        .key(key)
                        .contentType("application/json")
                        // S3 requires Content-MD5 (or a checksum) on any upload that sets a retention period; MD5 is accepted by every S3-compatible store.
                        .contentMD5(digestBase64("MD5", body))
                        .objectLockMode(mode)
                        .objectLockRetainUntilDate(Instant.now().plus(Duration.ofDays(properties.getRetentionDays())))
                        .build(),
                RequestBody.fromString(body, StandardCharsets.UTF_8));
    }

    /** The algorithm is a parameter only so the impossible failure path is testable. */
    static String digestBase64(String algorithm, String body) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is required by the JVM specification.", e);
        }
    }

    private List<Anchor> readAll(UUID organisationId) {
        String prefix = properties.getPrefix() + organisationId + "/";
        List<ObjectVersion> versions = new ArrayList<>();
        String keyMarker = null;
        String versionMarker = null;
        boolean more = true;
        while (more) {
            ListObjectVersionsResponse page = s3.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(properties.getBucket()).prefix(prefix).keyMarker(keyMarker).versionIdMarker(versionMarker).build());
            versions.addAll(page.versions());
            if (!page.deleteMarkers().isEmpty()) {
                log.error("[ANCHOR] The anchor store holds delete markers for organisation {}: someone tried to remove anchors. "
                        + "The locked versions are still read.", organisationId);
            }
            more = Boolean.TRUE.equals(page.isTruncated());
            keyMarker = page.nextKeyMarker();
            versionMarker = page.nextVersionIdMarker();
        }
        List<Anchor> anchors = new ArrayList<>();
        for (ObjectVersion version : versions) {
            byte[] bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.getBucket()).key(version.key()).versionId(version.versionId()).build()).asByteArray();
            anchors.add(parse(new String(bytes, StandardCharsets.UTF_8)));
        }
        anchors.sort(Comparator.comparingLong(Anchor::headSequence).thenComparing(Anchor::anchoredAt));
        return anchors;
    }

    private Anchor parse(String text) {
        try {
            return json.readValue(text, AnchorLine.class).toAnchor();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("The anchor store holds an object that cannot be read: it was corrupted or tampered with.", e);
        }
    }
}

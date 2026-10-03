package com.thinklab.infrastructure.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Binds {@code ledger.anchor.s3.*} (ADR-035): the S3-compatible bucket anchors are written to. The bucket must have Object Lock
 * enabled (that can only be set when the bucket is created); each anchor is written as its own object that cannot be overwritten or
 * deleted until its retention ends, even by the account that wrote it (COMPLIANCE mode).
 */
@ConfigurationProperties("ledger.anchor.s3")
public class AnchorS3Properties {

    private String endpoint = "";
    private String region = "us-east-1";
    private String bucket = "";
    private String prefix = "anchors/";
    private boolean pathStyle = false;
    private String accessKey = "";
    private String secretKey = "";
    private int retentionDays = 3650;
    private String mode = "COMPLIANCE";

    /** Blank for AWS; the URL of the store otherwise (MinIO, Ceph, ...). */
    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    /** Key prefix inside the bucket; one folder per organisation is added under it. */
    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    /** True for stores that do not support virtual-hosted bucket names (MinIO). */
    public boolean isPathStyle() {
        return pathStyle;
    }

    public void setPathStyle(boolean pathStyle) {
        this.pathStyle = pathStyle;
    }

    /** Blank to use the standard AWS credential chain (instance role, environment, ...). */
    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    /** How long each anchor object is locked; it cannot be shortened or removed before then. */
    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    /** {@code COMPLIANCE} (nobody can remove it, not even the root account) or {@code GOVERNANCE} (privileged users can). */
    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}

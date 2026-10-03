package com.thinklab.infrastructure.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds {@code ledger.anchor.*} (ADR-034, ADR-035): whether the chain heads are anchored outside the database, where, and under
 * which key. Off by default, so a deployment without an anchor destination behaves exactly as before.
 */
@ConfigurationProperties("ledger.anchor")
public class AnchorProperties {

    private boolean enabled = false;
    private String sink = "file";
    private String directory = "./anchors";
    private String key = "";
    private String keyId = "k1";
    private Map<String, String> previousKeys = new LinkedHashMap<>();
    private String previousKeysList = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Where anchors are published: {@code file} (an append-only file per tenant, ADR-034) or {@code s3} (an object store with Object Lock, ADR-035). */
    public String getSink() {
        return sink;
    }

    public void setSink(String sink) {
        this.sink = sink;
    }

    /** Where the file adapter appends anchors: mount a write-once volume here in production. */
    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    /** HMAC key the anchors are signed with. Must NOT be stored in (or readable with the credentials of) the ledger's database. */
    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    /** The id written into every anchor the current key signs, so the key can be rotated (ADR-035). */
    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    /** Retired keys by key id ({@code ledger.anchor.previous-keys.k1=...}): they no longer sign, they only verify what they signed. */
    public Map<String, String> getPreviousKeys() {
        return previousKeys;
    }

    public void setPreviousKeys(Map<String, String> previousKeys) {
        this.previousKeys = previousKeys;
    }

    /** The same retired keys as one environment-friendly string: comma separated {@code id=key} pairs (keys must not contain commas). */
    public String getPreviousKeysList() {
        return previousKeysList;
    }

    public void setPreviousKeysList(String previousKeysList) {
        this.previousKeysList = previousKeysList;
    }
}

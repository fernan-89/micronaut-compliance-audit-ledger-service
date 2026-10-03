package com.thinklab.infrastructure.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Binds {@code ledger.anchor.*} (ADR-034): whether the chain heads are anchored outside the database, where, and under which key.
 * Off by default, so a deployment without an anchor destination behaves exactly as before.
 */
@ConfigurationProperties("ledger.anchor")
public class AnchorProperties {

    private boolean enabled = false;
    private String directory = "./anchors";
    private String key = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
}

package com.thinklab.infrastructure.adapter.out.persistence;

/** Lets integration tests in other packages use the suite's shared MongoDB (the container class itself is package-private). */
public final class MongoContainerAccess {

    private MongoContainerAccess() {
    }

    public static String uri(String database) {
        return MongoContainer.uri(database);
    }
}

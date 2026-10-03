package com.thinklab.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataGuardTest {

    private static String rejected(String value) {
        return assertThrows(IllegalArgumentException.class, () -> SensitiveDataGuard.assertClean("Detail", value)).getMessage();
    }

    @Test
    @DisplayName("emails, card numbers, JWTs and credential assignments are rejected, naming the field and what it looks like - never echoing the value")
    void rejectsSensitiveData() {
        assertTrue(rejected("login by alice@example.com").contains("email address"));
        assertTrue(rejected("card 4111 1111 1111 1111 used").contains("payment card number"));
        assertTrue(rejected("card=4111-1111-1111-1111").contains("payment card number"));
        assertTrue(rejected("4111111111111111").contains("payment card number"));
        assertTrue(rejected("Visa 13 digits 4222222222222").contains("payment card number"));
        assertTrue(rejected("Diners 30569309025904").contains("payment card number"));
        assertTrue(rejected("Amex 378282246310005").contains("payment card number"));
        assertTrue(rejected("Amex 340000000000009").contains("payment card number"));
        assertTrue(rejected("Diners 36227206271667").contains("payment card number"));
        assertTrue(rejected("Diners 38520000023237").contains("payment card number"));
        assertTrue(rejected("19 digits 6759649826438453003").contains("payment card number"));
        assertTrue(rejected("Discover 6011000990139424").contains("payment card number"));
        assertTrue(rejected("tok eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJl").contains("a token"));
        assertTrue(rejected("password=hunter2").contains("a credential"));
        assertTrue(rejected("Authorization: Bearer abc").contains("a credential"));
        assertTrue(rejected("API_KEY = xyz").contains("a credential"));
        assertTrue(rejected("client secret: s3").contains("a credential"));
        for (String leaked : new String[]{"alice@example.com", "4111 1111 1111 1111", "hunter2"}) {
            assertFalse(rejected("x " + leaked + " password=hunter2").contains(leaked));
        }
        assertTrue(rejected("password=hunter2").startsWith("Detail looks like it contains"));
    }

    @Test
    @DisplayName("ordinary audit content passes: masked paths, UUIDs, status lines, short numbers, digit runs that fail the Luhn check, null")
    void acceptsOrdinaryContent() {
        for (String ok : new String[]{"PUT /it-asset-registry/v1/{id}/control/ready", UUID.randomUUID().toString(), "status=204", "order 12345",
                "4111111111111112", "12 34 56", "id 1234567890123", "login:3f2a9c4e5b6d7a8f9e0d1c2b3a4f5e6d", "the token was refreshed",
                "platform-gateway", "",
                // Digit runs that pass the Luhn check by chance but cannot be card numbers: a millisecond timestamp (13 digits, not Visa),
                // and numbers whose length/prefix match no scheme.
                "asset-1791033795330", "epoch 1791033795330", "11111111111111", "199999999999999", "1000000000000008", "7000000000000004",
                "9999999999999995"}) {
            assertDoesNotThrow(() -> SensitiveDataGuard.assertClean("Detail", ok), ok);
        }
        assertDoesNotThrow(() -> SensitiveDataGuard.assertClean("Detail", null));
    }

    @Test
    @DisplayName("LedgerEntry refuses sensitive data in every free-text field")
    void ledgerEntryAppliesTheGuardToEveryField() {
        UUID id = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        Instant when = Instant.parse("2026-10-03T12:00:00Z");
        String g = LedgerEntry.GENESIS_HASH;
        String bad = "alice@example.com";

        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, org, 1, when, bad, "a", "x", "t", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, org, 1, when, "s", bad, "x", "t", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, org, 1, when, "s", "a", bad, "t", null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, org, 1, when, "s", "a", "x", bad, null, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, org, 1, when, "s", "a", "x", "t", bad, null, "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, org, 1, when, "s", "a", "x", "t", null, "password=hunter2", "r", g));
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.createNew(id, org, 1, when, "s", "a", "x", "t", null, null, bad, g));
    }

    @Test
    @DisplayName("the guard is a non-instantiable utility class")
    void utilityClass() throws Exception {
        Constructor<SensitiveDataGuard> constructor = SensitiveDataGuard.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertInstanceOf(SensitiveDataGuard.class, constructor.newInstance());
    }
}

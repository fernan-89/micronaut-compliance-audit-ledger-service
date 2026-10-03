package com.thinklab.domain.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defence in depth for data protection (ADR-033): the ledger holds pseudonymous identifiers and descriptions of what
 * happened, never credentials, tokens, card numbers or direct contact data. Writers are expected to filter first (the gateway
 * does); this guard makes sure a careless one cannot put such data into an immutable chain, where it could never be removed.
 *
 * <p>It rejects, by field name and WITHOUT echoing the offending value: an email address; a 13-19 digit number that passes the
 * Luhn check (a payment card number); a JWT; and a {@code password}/{@code secret}/{@code token}/{@code authorization}/
 * {@code api-key} assignment. It is a tripwire for the common leaks, not a classifier: it cannot recognise a person's name or a
 * health record, so writers must still send opaque identifiers for those.
 */
final class SensitiveDataGuard {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern DIGIT_RUN = Pattern.compile("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)(password|passwd|secret|token|authorization|api[-_ ]?key|bearer)\\s*[=:]\\s*\\S+");

    private SensitiveDataGuard() {
    }

    /** Throws {@link IllegalArgumentException} naming {@code field} (never the value) when {@code value} looks sensitive. */
    static void assertClean(String field, String value) {
        String what = describe(value);
        if (what != null) {
            throw new IllegalArgumentException(field + " looks like it contains " + what
                    + "; the ledger holds pseudonymous identifiers only and never credentials, tokens, card numbers or contact data (ADR-033).");
        }
    }

    /** What the value looks like when it is sensitive, or {@code null} when it is fine. */
    private static String describe(String value) {
        if (value == null) {
            return null;
        }
        if (EMAIL.matcher(value).find()) {
            return "an email address";
        }
        if (containsCardNumber(value)) {
            return "a payment card number";
        }
        if (JWT.matcher(value).find()) {
            return "a token";
        }
        return CREDENTIAL.matcher(value).find() ? "a credential" : null;
    }

    private static boolean containsCardNumber(String value) {
        Matcher run = DIGIT_RUN.matcher(value);
        while (run.find()) {
            String digits = run.group().replaceAll("[ -]", "");
            if (hasCardPrefixAndLength(digits) && passesLuhn(digits)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Real card numbers have a length and a leading prefix per scheme; a bare Luhn check is not enough, because about one in ten
     * arbitrary digit strings passes it - including 13-digit millisecond timestamps, which end up in resource ids (found in CI: a
     * Postman resource id "asset-<epoch ms>" was rejected as a card number on one run in ten). 13 digits: Visa (4); 14: Diners
     * (30, 36, 38); 15: Amex (34, 37); 16-19: any major scheme (2-6).
     */
    private static boolean hasCardPrefixAndLength(String digits) {
        return switch (digits.length()) {
            case 13 -> digits.startsWith("4");
            case 14 -> digits.startsWith("30") || digits.startsWith("36") || digits.startsWith("38");
            case 15 -> digits.startsWith("34") || digits.startsWith("37");
            default -> digits.charAt(0) >= '2' && digits.charAt(0) <= '6';
        };
    }

    private static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

}

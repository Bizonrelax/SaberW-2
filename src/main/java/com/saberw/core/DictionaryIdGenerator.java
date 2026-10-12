package com.saberw.core;

import java.security.SecureRandom;

/** Dictionary ID generator (see Technical Specification section 5): 6 alphanumeric characters, A-Z a-z 0-9. */
public final class DictionaryIdGenerator {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    public static final int ID_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();

    /** Generates a random 6-char alphanumeric ID and checks uniqueness against the given predicate. */
    public String next(java.util.function.Predicate<String> isUnique) {
        for (int attempt = 0; attempt < 10_000; attempt++) {
            StringBuilder sb = new StringBuilder(ID_LENGTH);
            for (int i = 0; i < ID_LENGTH; i++) {
                sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
            String id = sb.toString();
            if (isUnique.test(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Could not generate a unique dictionary ID");
    }
}

package io.github.benjaminursu.bankimport.service;

/**
 * Identifies one transaction for duplicate detection. Stored as transactions.dedup_key and
 * transactions.occurrence, and unique per account in the database.
 *
 * @param hash       SHA-256 hex of the transaction's content (see {@link DuplicateDetector#contentHash})
 * @param occurrence 1 for the first row with this hash in a file, 2 for the second identical one, ...
 */
public record DuplicateKey(String hash, int occurrence) {

    public DuplicateKey {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("hash must be 64 lower-case hex characters");
        }
        if (occurrence < 1) {
            throw new IllegalArgumentException("occurrence must be >= 1");
        }
    }
}

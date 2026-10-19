package io.github.benjaminursu.bankimport.service;

import io.github.benjaminursu.bankimport.model.Transaction;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides which transactions of a new import are already stored.
 * <p>
 * The problem: bank exports overlap (you export January, then January 15 to February), so the same
 * transaction arrives twice and must be stored once. But two <em>real</em> payments can look
 * exactly the same (two coffees, same bar, same day, same amount), and those must both be stored.
 * Banks like KBC give no unique transaction ID to tell them apart.
 * <p>
 * The solution: count. Each transaction gets a content hash, plus an occurrence number that counts
 * how many times that hash has already appeared <em>earlier in the same file</em>. Two identical
 * coffees in one file become (hash, 1) and (hash, 2): different keys, both stored. A later
 * overlapping export that contains the same two coffees produces (hash, 1) and (hash, 2) again:
 * both already stored, both skipped. If it contains three, the third is (hash, 3) and is new.
 * <p>
 * Assumption: an export contains every transaction of each day it covers, in the bank's order.
 * Exports are by date range, so they do. See REVIEW.md for the one case where this would break.
 * <p>
 * Pure logic, no database: the caller loads the keys already stored for the account.
 */
@Service
public class DuplicateDetector {

    // ASCII "unit separator". Never appears in bank text, so "AB" + "C" and "A" + "BC" hash differently.
    private static final String FIELD_SEPARATOR = "\u001F";

    /** A transaction plus its duplicate key. */
    public record KeyedTransaction(Transaction transaction, DuplicateKey key) {
    }

    /** {@code toImport}: not stored yet. {@code duplicates}: already stored, skip them. Both in file order. */
    public record Result(List<KeyedTransaction> toImport, List<KeyedTransaction> duplicates) {
    }

    /**
     * @param incoming      transactions of one file, in file order (order matters for the occurrence count)
     * @param alreadyStored keys already in the database for this account
     */
    public Result check(List<Transaction> incoming, Set<DuplicateKey> alreadyStored) {
        List<KeyedTransaction> toImport = new ArrayList<>();
        List<KeyedTransaction> duplicates = new ArrayList<>();
        for (KeyedTransaction keyed : assignKeys(incoming)) {
            if (alreadyStored.contains(keyed.key())) {
                duplicates.add(keyed);
            } else {
                toImport.add(keyed);
            }
        }
        return new Result(toImport, duplicates);
    }

    /** Gives each transaction its (hash, occurrence) key. Occurrences count up per hash, in file order. */
    public List<KeyedTransaction> assignKeys(List<Transaction> transactions) {
        Map<String, Integer> seen = new HashMap<>();
        List<KeyedTransaction> result = new ArrayList<>(transactions.size());
        for (Transaction transaction : transactions) {
            String hash = contentHash(transaction);
            int occurrence = seen.merge(hash, 1, Integer::sum);
            result.add(new KeyedTransaction(transaction, new DuplicateKey(hash, occurrence)));
        }
        return result;
    }

    /**
     * SHA-256 of the fields that describe <em>what happened</em>: dates, amount, currency,
     * counterparty account and description.
     * <p>
     * Deliberately left out:
     * <ul>
     *   <li>balance after: it would make identical payments differ, but it changes if the bank lists
     *       same-day rows in a different order in a later export, which would import them twice</li>
     *   <li>statement number: may not be assigned yet when a recent period is exported</li>
     *   <li>source row: differs in every file by definition</li>
     *   <li>counterparty name: already part of the description for KBC, and banks change its spelling</li>
     * </ul>
     * Small formatting differences are normalised so they do not create a "new" transaction:
     * 3.5 and 3.50, extra spaces, upper/lower case, spaces inside account numbers.
     */
    public String contentHash(Transaction t) {
        String content = String.join(FIELD_SEPARATOR,
                t.bookingDate().toString(),
                dateOrEmpty(t.valueDate()),
                t.amount().amount().stripTrailingZeros().toPlainString(),
                t.amount().currency().getCurrencyCode(),
                normaliseAccount(t.counterpartyAccount()),
                normaliseText(t.description()));
        return sha256Hex(content);
    }

    private static String dateOrEmpty(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    private static String normaliseAccount(String account) {
        return account == null ? "" : account.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    private static String normaliseText(String text) {
        return text.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}

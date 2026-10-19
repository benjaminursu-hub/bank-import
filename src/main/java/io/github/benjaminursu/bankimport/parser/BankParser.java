package io.github.benjaminursu.bankimport.parser;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads one bank's export format into the common {@link ParsedStatement}.
 * One implementation per format (KBC CSV, Belfius CSV, maib PDF, ...).
 * <p>
 * Implementations must either return a fully checked statement or throw
 * {@link BankFileFormatException}. Never return a "best effort" half-parsed file:
 * a silently wrong amount is worse than a refused import.
 */
public interface BankParser {

    /** Stable short name, stored in import_batches.parser. Example: {@code "kbc-csv"}. */
    String id();

    /** Does not close the stream; the caller owns it. */
    ParsedStatement parse(InputStream input) throws IOException;
}

package io.github.benjaminursu.bankimport.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One booked movement on an account, in a bank-independent shape. Every parser produces these.
 *
 * @param bookingDate         date the bank booked it (always present)
 * @param valueDate           date interest counts from; may differ from booking date, may be null
 * @param amount              signed: negative = money out, positive = money in
 * @param balanceAfter        balance right after this transaction as the bank reports it; null if unknown
 * @param counterpartyName    other party's name; null if the bank does not give one (e.g. card payments)
 * @param counterpartyAccount other party's account number; null if unknown
 * @param description         the bank's free text, never null (empty if none)
 * @param statementNumber     bank statement this row belongs to; shared by several rows, may be null
 * @param sourceRow           1-based line number in the source file (header is line 1), for error reports
 */
public record Transaction(
        LocalDate bookingDate,
        LocalDate valueDate,
        Money amount,
        Money balanceAfter,
        String counterpartyName,
        String counterpartyAccount,
        String description,
        String statementNumber,
        int sourceRow) {

    public Transaction {
        Objects.requireNonNull(bookingDate, "bookingDate");
        Objects.requireNonNull(amount, "amount");
        description = description == null ? "" : description;
        if (balanceAfter != null && !balanceAfter.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("balanceAfter currency differs from amount currency");
        }
        if (sourceRow < 1) {
            throw new IllegalArgumentException("sourceRow must be >= 1");
        }
    }

    /**
     * Deliberately hides the contents. A record's default toString prints every field, and one
     * careless log line would leak names, IBANs and descriptions. Log the row number instead.
     */
    @Override
    public String toString() {
        return "Transaction[row " + sourceRow + "]";
    }
}

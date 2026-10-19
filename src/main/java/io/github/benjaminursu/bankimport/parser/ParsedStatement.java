package io.github.benjaminursu.bankimport.parser;

import io.github.benjaminursu.bankimport.model.Money;
import io.github.benjaminursu.bankimport.model.Transaction;

import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Result of parsing one export file: which account it belongs to, and its transactions in file order.
 *
 * @param openingBalance balance before the first transaction, null if the format has no balances
 * @param closingBalance balance after the last transaction, null if the format has no balances
 */
public record ParsedStatement(
        String accountNumber,
        Currency currency,
        List<Transaction> transactions,
        Money openingBalance,
        Money closingBalance) {

    public ParsedStatement {
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(currency, "currency");
        transactions = List.copyOf(transactions);
    }

    @Override
    public String toString() {
        return "ParsedStatement[" + transactions.size() + " transactions, " + currency.getCurrencyCode() + "]";
    }
}

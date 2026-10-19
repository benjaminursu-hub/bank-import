package io.github.benjaminursu.bankimport.parser;

import io.github.benjaminursu.bankimport.model.Money;
import io.github.benjaminursu.bankimport.model.Transaction;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parser for the KBC (Belgium) CSV export.
 * <p>
 * Traps this class handles, each one tested in KbcCsvParserTest:
 * <ol>
 *   <li>Line endings are CR only. Most CSV readers split on LF and see one giant line.</li>
 *   <li>The header has 18 columns but every data row has 19 (trailing {@code ;}).</li>
 *   <li>Decimal comma: {@code -18,99}.</li>
 *   <li>Dates are {@code dd/MM/yyyy}; booking date and value date can differ.</li>
 *   <li>{@code Amount} is signed and {@code Credit}/{@code Debit} repeat it: both must agree.</li>
 *   <li>{@code Balance} after every row, oldest first: previous balance + amount must equal balance.</li>
 *   <li>No unique ID, and {@code Statement number} is shared by several rows.</li>
 *   <li>Encoding unconfirmed: UTF-8 is tried first, Windows-1252 is the fallback.</li>
 *   <li>Reversals ("CORRECTING ENTRY ...") are ordinary rows with the opposite sign. They are kept.</li>
 * </ol>
 */
@Component
public class KbcCsvParser implements BankParser {

    // Header names, compared case-insensitively. Columns are found by name, not position,
    // so a re-ordered export still works and a renamed column fails loudly.
    static final String COL_ACCOUNT = "account number";
    static final String COL_CURRENCY = "currency";
    static final String COL_STATEMENT = "statement number";
    static final String COL_DATE = "date";
    static final String COL_DESCRIPTION = "description";
    static final String COL_VALUE_DATE = "value date";
    static final String COL_AMOUNT = "amount";
    static final String COL_BALANCE = "balance";
    static final String COL_CREDIT = "credit";
    static final String COL_DEBIT = "debit";
    static final String COL_COUNTERPARTY_ACCOUNT = "counterparty's account number";
    static final String COL_COUNTERPARTY_NAME = "counterparty's name";

    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_ACCOUNT, COL_CURRENCY, COL_STATEMENT, COL_DATE, COL_DESCRIPTION, COL_VALUE_DATE,
            COL_AMOUNT, COL_BALANCE, COL_CREDIT, COL_DEBIT, COL_COUNTERPARTY_ACCOUNT, COL_COUNTERPARTY_NAME);

    private static final char SEPARATOR = ';';

    // STRICT + 'uuuu': rejects 31/02/2026 instead of quietly turning it into 28/02.
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    // "-18,99", "1500,00", "1.500,00", "12". Nothing else: "1.500" (thousands or decimals?) is refused.
    private static final Pattern AMOUNT = Pattern.compile("[+-]?(\\d+|\\d{1,3}(\\.\\d{3})+)(,\\d+)?");

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    @Override
    public String id() {
        return "kbc-csv";
    }

    @Override
    public ParsedStatement parse(InputStream input) throws IOException {
        String text = decode(input.readAllBytes());

        // Trap 1: split on CR, LF or CRLF, so CR-only files work. -1 keeps trailing empty strings,
        // which keeps line numbers correct; blank lines are skipped below.
        String[] lines = text.split("\r\n|\r|\n", -1);

        Header header = readHeader(lines[0]);

        List<Transaction> transactions = new ArrayList<>();
        String accountNumber = null;
        Currency currency = null;

        for (int i = 1; i < lines.length; i++) {
            int lineNo = i + 1;
            if (lines[i].isBlank()) {
                continue;
            }
            String[] fields = splitFields(lines[i], lineNo);
            fields = dropTrailingSeparator(fields, header.size(), lineNo);
            Row row = new Row(fields, header.columns(), lineNo);

            String rowAccount = row.required(COL_ACCOUNT);
            Currency rowCurrency = parseCurrency(row.required(COL_CURRENCY), lineNo);
            if (accountNumber == null) {
                accountNumber = rowAccount;
                currency = rowCurrency;
            } else if (!accountNumber.equals(rowAccount)) {
                throw new BankFileFormatException(lineNo, "account number differs from the first row");
            } else if (!currency.equals(rowCurrency)) {
                throw new BankFileFormatException(lineNo, "currency differs from the first row");
            }

            Money amount = new Money(parseAmount(row.required(COL_AMOUNT), COL_AMOUNT, lineNo), currency);
            checkCreditDebit(amount.amount(), row.optional(COL_CREDIT), row.optional(COL_DEBIT), lineNo);
            Money balance = new Money(parseAmount(row.required(COL_BALANCE), COL_BALANCE, lineNo), currency);

            transactions.add(new Transaction(
                    parseDate(row.required(COL_DATE), COL_DATE, lineNo),
                    row.optional(COL_VALUE_DATE) == null ? null
                            : parseDate(row.optional(COL_VALUE_DATE), COL_VALUE_DATE, lineNo),
                    amount,
                    balance,
                    row.optional(COL_COUNTERPARTY_NAME),
                    row.optional(COL_COUNTERPARTY_ACCOUNT),
                    row.optional(COL_DESCRIPTION),
                    row.optional(COL_STATEMENT),
                    lineNo));
        }

        if (transactions.isEmpty()) {
            throw new BankFileFormatException(0, "file contains no transactions");
        }
        checkBalanceChain(transactions);

        Transaction first = transactions.getFirst();
        Transaction last = transactions.getLast();
        return new ParsedStatement(accountNumber, currency, transactions,
                first.balanceAfter().minus(first.amount()), last.balanceAfter());
    }

    /**
     * Trap 8: the encoding is not confirmed. Strict UTF-8 first: a Windows-1252 file with é/ë is
     * almost never valid UTF-8, so a decoding error is a reliable sign to fall back. Pure ASCII
     * files decode the same either way.
     */
    static String decode(byte[] bytes) {
        int start = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            start = 3; // UTF-8 byte order mark
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes, start, bytes.length - start);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer)
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, WINDOWS_1252);
        }
    }

    private static Header readHeader(String headerLine) {
        if (headerLine.isBlank()) {
            throw new BankFileFormatException(1, "missing header");
        }
        String[] names = splitFields(headerLine, 1);
        // Trap 2: tolerate a trailing ';' on the header too, in case KBC ever adds one.
        int size = names.length;
        while (size > 0 && names[size - 1].isBlank()) {
            size--;
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < size; i++) {
            columns.putIfAbsent(names[i].trim().toLowerCase(Locale.ROOT), i);
        }
        List<String> missing = REQUIRED_COLUMNS.stream().filter(c -> !columns.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            // Column names are not personal data, so they may appear in the message.
            throw new BankFileFormatException(1, "not a KBC export, missing columns " + missing);
        }
        return new Header(columns, size);
    }

    /**
     * Trap 2: data rows have one more field than the header, and that field must be empty.
     * Rows with exactly the header's size are accepted too. Anything else means the columns
     * have shifted, and every value would be read from the wrong column.
     */
    private static String[] dropTrailingSeparator(String[] fields, int headerSize, int lineNo) {
        if (fields.length == headerSize + 1 && fields[headerSize].isBlank()) {
            return Arrays.copyOf(fields, headerSize);
        }
        if (fields.length == headerSize) {
            return fields;
        }
        throw new BankFileFormatException(lineNo,
                "expected " + (headerSize + 1) + " fields, found " + fields.length);
    }

    /**
     * Splits on ';'. A field in double quotes may contain ';', and "" inside quotes is a literal
     * quote (standard CSV rules). The KBC sample had no quotes, but a description containing ';'
     * would otherwise shift every column after it.
     */
    static String[] splitFields(String line, int lineNo) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"' && current.isEmpty()) {
                inQuotes = true;
            } else if (c == SEPARATOR) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (inQuotes) {
            throw new BankFileFormatException(lineNo, "unclosed quote");
        }
        fields.add(current.toString());
        return fields.toArray(String[]::new);
    }

    /** Trap 3: decimal comma, optional '.' as thousands separator in groups of exactly three. */
    static BigDecimal parseAmount(String raw, String column, int lineNo) {
        String value = raw.replace(" ", "");
        if (!AMOUNT.matcher(value).matches()) {
            throw new BankFileFormatException(lineNo, "column '" + column + "' is not a valid amount");
        }
        return new BigDecimal(value.replace(".", "").replace(',', '.'));
    }

    private static LocalDate parseDate(String raw, String column, int lineNo) {
        try {
            return LocalDate.parse(raw, DATE);
        } catch (DateTimeParseException e) {
            throw new BankFileFormatException(lineNo, "column '" + column + "' is not a dd/MM/yyyy date");
        }
    }

    private static Currency parseCurrency(String raw, int lineNo) {
        try {
            return Currency.getInstance(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BankFileFormatException(lineNo, "unknown currency code");
        }
    }

    /**
     * Trap 5: Credit/Debit repeat the amount. Money in: Credit equals it and Debit is empty.
     * Money out: Debit equals it (KBC may or may not sign it, so compare the absolute value)
     * and Credit is empty. A mismatch means we are reading the wrong column or a corrupt file.
     */
    private static void checkCreditDebit(BigDecimal amount, String creditRaw, String debitRaw, int lineNo) {
        BigDecimal credit = creditRaw == null ? BigDecimal.ZERO : parseAmount(creditRaw, COL_CREDIT, lineNo).abs();
        BigDecimal debit = debitRaw == null ? BigDecimal.ZERO : parseAmount(debitRaw, COL_DEBIT, lineNo).abs();
        BigDecimal expectedCredit = amount.signum() > 0 ? amount : BigDecimal.ZERO;
        BigDecimal expectedDebit = amount.signum() < 0 ? amount.abs() : BigDecimal.ZERO;
        if (credit.compareTo(expectedCredit) != 0 || debit.compareTo(expectedDebit) != 0) {
            throw new BankFileFormatException(lineNo, "Credit/Debit columns do not match Amount");
        }
    }

    /**
     * Trap 6: each balance must equal the previous balance plus this amount. This is the strongest
     * check we have: a misread decimal, a skipped row or a wrong column all break the chain.
     * It also proves the file is oldest-first.
     */
    private static void checkBalanceChain(List<Transaction> transactions) {
        for (int i = 1; i < transactions.size(); i++) {
            Money expected = transactions.get(i - 1).balanceAfter().plus(transactions.get(i).amount());
            if (!expected.equals(transactions.get(i).balanceAfter())) {
                throw new BankFileFormatException(transactions.get(i).sourceRow(),
                        "balance does not equal previous balance plus amount");
            }
        }
    }

    /** Lower-case column name to index, and the number of real (non-empty) header columns. */
    private record Header(Map<String, Integer> columns, int size) {
    }

    /** Field access by column name. Values are trimmed; empty becomes null. */
    private record Row(String[] fields, Map<String, Integer> columns, int lineNo) {

        String optional(String column) {
            String value = fields[columns.get(column)].trim();
            return value.isEmpty() ? null : value;
        }

        String required(String column) {
            String value = optional(column);
            if (value == null) {
                throw new BankFileFormatException(lineNo, "column '" + column + "' is empty");
            }
            return value;
        }
    }
}

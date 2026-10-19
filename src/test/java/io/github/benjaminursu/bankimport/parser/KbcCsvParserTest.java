package io.github.benjaminursu.bankimport.parser;

import io.github.benjaminursu.bankimport.model.Money;
import io.github.benjaminursu.bankimport.model.Transaction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KbcCsvParserTest {

    private static final Path FIXTURES = Path.of("fixtures", "kbc");

    private static final String HEADER = "Account number;Heading;Name;Currency;Statement number;Date;Description;"
            + "Value date;Amount;Balance;Credit;Debit;Counterparty's account number;BIC counterparty;"
            + "Counterparty's name;Counterparty's address;Structured reference;Free-format reference";

    private final KbcCsvParser parser = new KbcCsvParser();

    // ---------- the fixtures ----------

    @Test
    void basicFixtureIsReallyCrOnly() throws IOException {
        // Guards the fixture itself: if an editor or git ever "fixes" the line endings,
        // the CR-only test below would silently stop testing anything.
        byte[] bytes = Files.readAllBytes(FIXTURES.resolve("kbc-basic.csv"));
        assertThat(bytes).contains((byte) '\r').doesNotContain((byte) '\n');
    }

    @Test
    void parsesCrOnlyFileIntoSeparateRows() throws IOException {
        ParsedStatement statement = parseFixture("kbc-basic.csv");

        assertThat(statement.transactions()).hasSize(8);
        assertThat(statement.accountNumber()).isEqualTo("BE00123400000001");
        assertThat(statement.currency().getCurrencyCode()).isEqualTo("EUR");
    }

    @Test
    void readsDecimalCommaAmountsAndBalances() throws IOException {
        Transaction supermarket = parseFixture("kbc-basic.csv").transactions().get(1);

        assertThat(supermarket.amount()).isEqualTo(Money.of("-18.99", "EUR"));
        assertThat(supermarket.balanceAfter()).isEqualTo(Money.of("1981.01", "EUR"));
    }

    @Test
    void keepsBookingDateAndValueDateApart() throws IOException {
        Transaction supermarket = parseFixture("kbc-basic.csv").transactions().get(1);

        assertThat(supermarket.bookingDate()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(supermarket.valueDate()).isEqualTo(LocalDate.of(2026, 1, 3));
    }

    @Test
    void computesOpeningAndClosingBalanceFromTheChain() throws IOException {
        ParsedStatement statement = parseFixture("kbc-basic.csv");

        assertThat(statement.openingBalance()).isEqualTo(Money.of("500.00", "EUR"));
        assertThat(statement.closingBalance()).isEqualTo(Money.of("1074.01", "EUR"));
    }

    @Test
    void readsCounterpartyAndKeepsEmptyOnesAsNull() throws IOException {
        List<Transaction> rows = parseFixture("kbc-basic.csv").transactions();

        Transaction salary = rows.getFirst();
        assertThat(salary.counterpartyName()).isEqualTo("FAKE EMPLOYER NV");
        assertThat(salary.counterpartyAccount()).isEqualTo("BE00999900000001");
        assertThat(salary.statementNumber()).isEqualTo("00001");

        Transaction cardPayment = rows.get(1);
        assertThat(cardPayment.counterpartyName()).isNull();
        assertThat(cardPayment.counterpartyAccount()).isNull();
    }

    @Test
    void keepsBothIdenticalCoffeesAsSeparateTransactions() throws IOException {
        List<Transaction> rows = parseFixture("kbc-basic.csv").transactions();
        Transaction first = rows.get(2);
        Transaction second = rows.get(3);

        assertThat(first.description()).isEqualTo(second.description());
        assertThat(first.amount()).isEqualTo(second.amount());
        assertThat(first.sourceRow()).isEqualTo(4);
        assertThat(second.sourceRow()).isEqualTo(5);
    }

    @Test
    void keepsReversalAsItsOwnPositiveTransaction() throws IOException {
        List<Transaction> rows = parseFixture("kbc-basic.csv").transactions();
        Transaction purchase = rows.get(4);
        Transaction reversal = rows.get(5);

        assertThat(reversal.description()).startsWith("CORRECTING ENTRY");
        assertThat(reversal.amount()).isEqualTo(Money.of("42.00", "EUR"));
        assertThat(purchase.amount().plus(reversal.amount()).signum()).isZero();
    }

    @Test
    void readsAccentsFromUtf8AndWindows1252Identically() throws IOException {
        List<Transaction> utf8 = parseFixture("kbc-accents-utf8.csv").transactions();
        List<Transaction> cp1252 = parseFixture("kbc-accents-windows1252.csv").transactions();

        assertThat(utf8.get(0).description()).contains("CAFÉ NOËL");
        assertThat(utf8.get(1).counterpartyName()).isEqualTo("Zoë Lefèvre");
        for (int i = 0; i < utf8.size(); i++) {
            assertThat(cp1252.get(i).description()).isEqualTo(utf8.get(i).description());
            assertThat(cp1252.get(i).counterpartyName()).isEqualTo(utf8.get(i).counterpartyName());
        }
    }

    @Test
    void acceptsFileWithoutTrailingLineBreak() throws IOException {
        // kbc-accents-utf8.csv has no CR after its last row; kbc-basic.csv has one.
        assertThat(parseFixture("kbc-accents-utf8.csv").transactions()).hasSize(2);
    }

    // ---------- other line endings and small variations ----------

    @Test
    void alsoAcceptsCrlfAndLf() throws IOException {
        String row1 = row("02/01/2026", "-1,00", "99,00", "", "-1,00");
        String row2 = row("03/01/2026", "-2,00", "97,00", "", "-2,00");

        assertThat(parse(HEADER + "\r\n" + row1 + "\r\n" + row2 + "\r\n").transactions()).hasSize(2);
        assertThat(parse(HEADER + "\n" + row1 + "\n" + row2 + "\n").transactions()).hasSize(2);
    }

    @Test
    void acceptsUtf8ByteOrderMark() throws IOException {
        String file = "﻿" + HEADER + "\r" + row("02/01/2026", "-1,00", "99,00", "", "-1,00");
        assertThat(parse(file).transactions()).hasSize(1);
    }

    @Test
    void acceptsRowsWithoutTheTrailingSeparator() throws IOException {
        String withoutTrailing = row("02/01/2026", "-1,00", "99,00", "", "-1,00");
        withoutTrailing = withoutTrailing.substring(0, withoutTrailing.length() - 1);
        assertThat(parse(HEADER + "\r" + withoutTrailing).transactions()).hasSize(1);
    }

    @Test
    void acceptsThousandsSeparator() throws IOException {
        String file = HEADER + "\r" + row("02/01/2026", "-1.250,00", "2.000,50", "", "-1.250,00");
        assertThat(parse(file).transactions().getFirst().amount()).isEqualTo(Money.of("-1250.00", "EUR"));
    }

    @Test
    void acceptsUnsignedDebitColumn() throws IOException {
        String file = HEADER + "\r" + row("02/01/2026", "-1,00", "99,00", "", "1,00");
        assertThat(parse(file).transactions()).hasSize(1);
    }

    @Test
    void keepsSemicolonInsideQuotedField() throws IOException {
        String file = HEADER + "\r" + "BE00123400000001;Current account;J. DOE;EUR;00001;02/01/2026;"
                + "\"FAKE SHOP; AISLE \"\"3\"\"\";02/01/2026;-1,00;99,00;;-1,00;;;;;;;";
        assertThat(parse(file).transactions().getFirst().description()).isEqualTo("FAKE SHOP; AISLE \"3\"");
    }

    // ---------- things that must be refused, loudly ----------

    @Test
    void refusesBrokenBalanceChainAndNamesTheLine() {
        String file = HEADER
                + "\r" + row("02/01/2026", "-1,00", "99,00", "", "-1,00")
                + "\r" + row("03/01/2026", "-2,00", "96,00", "", "-2,00"); // should be 97,00

        assertThatThrownBy(() -> parse(file))
                .isInstanceOf(BankFileFormatException.class)
                .hasMessage("line 3: balance does not equal previous balance plus amount");
    }

    @Test
    void refusesNewestFirstFile() {
        // Same rows as a valid file, but in reverse order: the chain no longer adds up.
        String file = HEADER
                + "\r" + row("03/01/2026", "-2,00", "97,00", "", "-2,00")
                + "\r" + row("02/01/2026", "-1,00", "99,00", "", "-1,00");

        assertThatThrownBy(() -> parse(file)).isInstanceOf(BankFileFormatException.class);
    }

    @Test
    void refusesCreditDebitThatContradictAmount() {
        String debitWrong = HEADER + "\r" + row("02/01/2026", "-1,00", "99,00", "", "-1,50");
        String creditOnPayment = HEADER + "\r" + row("02/01/2026", "-1,00", "99,00", "1,00", "");

        assertThatThrownBy(() -> parse(debitWrong))
                .isInstanceOf(BankFileFormatException.class)
                .hasMessageContaining("Credit/Debit");
        assertThatThrownBy(() -> parse(creditOnPayment))
                .isInstanceOf(BankFileFormatException.class)
                .hasMessageContaining("Credit/Debit");
    }

    @Test
    void refusesShiftedColumns() {
        String extraField = HEADER + "\r" + row("02/01/2026", "-1,00", "99,00", "", "-1,00") + "x;";

        assertThatThrownBy(() -> parse(extraField))
                .isInstanceOf(BankFileFormatException.class)
                .hasMessage("line 2: expected 19 fields, found 20");
    }

    @Test
    void refusesAmbiguousOrDotDecimalAmounts() {
        assertThatThrownBy(() -> parse(HEADER + "\r" + row("02/01/2026", "-1.5", "98,50", "", "-1.5")))
                .isInstanceOf(BankFileFormatException.class)
                .hasMessageContaining("not a valid amount");
    }

    @Test
    void refusesImpossibleDate() {
        assertThatThrownBy(() -> parse(HEADER + "\r" + row("31/02/2026", "-1,00", "99,00", "", "-1,00")))
                .isInstanceOf(BankFileFormatException.class)
                .hasMessageContaining("dd/MM/yyyy");
    }

    @Test
    void refusesFileWithoutKbcHeader() {
        assertThatThrownBy(() -> parse("Date;Amount\r02/01/2026;-1,00"))
                .isInstanceOf(BankFileFormatException.class)
                .hasMessageStartingWith("line 1: not a KBC export");
    }

    @Test
    void refusesHeaderOnlyFile() {
        assertThatThrownBy(() -> parse(HEADER + "\r"))
                .isInstanceOf(BankFileFormatException.class)
                .hasMessage("file contains no transactions");
    }

    @Test
    void refusesTwoAccountsInOneFile() {
        String otherAccount = row("03/01/2026", "-2,00", "97,00", "", "-2,00")
                .replace("BE00123400000001", "BE00123400000002");
        String file = HEADER + "\r" + row("02/01/2026", "-1,00", "99,00", "", "-1,00") + "\r" + otherAccount;

        assertThatThrownBy(() -> parse(file))
                .isInstanceOf(BankFileFormatException.class)
                .hasMessage("line 3: account number differs from the first row");
    }

    @Test
    void errorMessagesNeverContainRowContents() {
        String file = HEADER
                + "\r" + row("02/01/2026", "-1,00", "99,00", "", "-1,00")
                + "\r" + row("03/01/2026", "-2,00", "12345,67", "", "-2,00");

        assertThatThrownBy(() -> parse(file))
                .hasMessageNotContaining("SECRET")
                .hasMessageNotContaining("BE00123400000001")
                .hasMessageNotContaining("12345");
    }

    @Test
    void transactionToStringHidesContents() throws IOException {
        Transaction salary = parseFixture("kbc-basic.csv").transactions().getFirst();
        assertThat(salary.toString()).isEqualTo("Transaction[row 2]");
    }

    // ---------- helpers ----------

    /** One data row with a trailing ';' (19 fields), like a real KBC export. */
    private static String row(String date, String amount, String balance, String credit, String debit) {
        return "BE00123400000001;Current account;J. DOE;EUR;00001;" + date + ";SECRET SHOP;" + date + ";"
                + amount + ";" + balance + ";" + credit + ";" + debit + ";;;;;;;";
    }

    private ParsedStatement parse(String content) throws IOException {
        return parser.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private ParsedStatement parseFixture(String name) throws IOException {
        try (InputStream in = Files.newInputStream(FIXTURES.resolve(name))) {
            return parser.parse(in);
        }
    }
}

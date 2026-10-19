package io.github.benjaminursu.bankimport.service;

import io.github.benjaminursu.bankimport.model.Money;
import io.github.benjaminursu.bankimport.model.Transaction;
import io.github.benjaminursu.bankimport.parser.KbcCsvParser;
import io.github.benjaminursu.bankimport.service.DuplicateDetector.KeyedTransaction;
import io.github.benjaminursu.bankimport.service.DuplicateDetector.Result;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateDetectorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 6);

    private final DuplicateDetector detector = new DuplicateDetector();

    // ---------- the rule that must never break ----------

    @Test
    void twoIdenticalRealPaymentsInOneFileAreBothImported() {
        Transaction coffee1 = coffee(4);
        Transaction coffee2 = coffee(5);

        Result result = detector.check(List.of(coffee1, coffee2), Set.of());

        assertThat(result.toImport()).hasSize(2);
        assertThat(result.duplicates()).isEmpty();
        DuplicateKey first = result.toImport().get(0).key();
        DuplicateKey second = result.toImport().get(1).key();
        assertThat(first.hash()).isEqualTo(second.hash());
        assertThat(first.occurrence()).isEqualTo(1);
        assertThat(second.occurrence()).isEqualTo(2);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void twoIdenticalPaymentsSurviveAReimportOfTheSameFile() {
        List<Transaction> file = List.of(coffee(4), coffee(5));
        Set<DuplicateKey> stored = store(detector.check(file, Set.of()).toImport());

        Result again = detector.check(file, stored);

        assertThat(again.toImport()).isEmpty();
        assertThat(again.duplicates()).hasSize(2);
        assertThat(stored).hasSize(2); // still two coffees in the database, not one
    }

    @Test
    void aThirdIdenticalPaymentInALaterExportIsNew() {
        // First export (taken at noon): two coffees. Later export of the same day: three.
        Set<DuplicateKey> stored = store(detector.check(List.of(coffee(4), coffee(5)), Set.of()).toImport());

        Result later = detector.check(List.of(coffee(2), coffee(3), coffee(4)), stored);

        assertThat(later.duplicates()).hasSize(2);
        assertThat(later.toImport()).hasSize(1);
        assertThat(later.toImport().getFirst().key().occurrence()).isEqualTo(3);
    }

    // ---------- overlapping exports, end to end with the KBC fixtures ----------

    @Test
    void overlappingKbcExportAddsOnlyTheNewRows() throws IOException {
        KbcCsvParser parser = new KbcCsvParser();
        List<Transaction> basic = parseFixture(parser, "kbc-basic.csv");
        List<Transaction> overlap = parseFixture(parser, "kbc-overlap.csv");

        Set<DuplicateKey> stored = store(detector.check(basic, Set.of()).toImport());
        assertThat(stored).hasSize(8); // both coffees kept

        Result second = detector.check(overlap, stored);

        // overlap.csv repeats 6 rows (including both identical coffees) and adds 2 new ones.
        assertThat(second.duplicates()).hasSize(6);
        assertThat(second.toImport()).extracting(k -> k.transaction().sourceRow()).containsExactly(8, 9);
    }

    // ---------- what counts as "the same" ----------

    @Test
    void differencesThatMatterGiveDifferentHashes() {
        Transaction base = coffee(2);
        String hash = detector.contentHash(base);

        assertThat(detector.contentHash(with(base, DAY.plusDays(1), base.valueDate(), "-3.50", base.description())))
                .as("booking date").isNotEqualTo(hash);
        assertThat(detector.contentHash(with(base, DAY, DAY.minusDays(1), "-3.50", base.description())))
                .as("value date").isNotEqualTo(hash);
        assertThat(detector.contentHash(with(base, DAY, DAY, "-3.60", base.description())))
                .as("amount").isNotEqualTo(hash);
        assertThat(detector.contentHash(with(base, DAY, DAY, "3.50", base.description())))
                .as("sign: a reversal is not a duplicate of the payment").isNotEqualTo(hash);
        assertThat(detector.contentHash(with(base, DAY, DAY, "-3.50", "FAKE TEA ROOM ANTWERPEN")))
                .as("description").isNotEqualTo(hash);
        assertThat(detector.contentHash(new Transaction(DAY, DAY, Money.of("-3.50", "USD"), null,
                null, null, base.description(), null, 2)))
                .as("currency").isNotEqualTo(hash);
        assertThat(detector.contentHash(new Transaction(DAY, DAY, base.amount(), null,
                null, "BE00999900000009", base.description(), null, 2)))
                .as("counterparty account").isNotEqualTo(hash);
    }

    @Test
    void formattingAndBookkeepingDetailsDoNotChangeTheHash() {
        Transaction base = coffee(2);
        Transaction reformatted = new Transaction(
                DAY, DAY,
                Money.of("-3.5", "EUR"),                       // 3.5 instead of 3.50
                Money.of("999.99", "EUR"),                     // different balance
                "Some Name",                                   // counterparty name is ignored
                null,
                "  payment via debit card number 9999 99XX XXXX 0001 06/01   fake coffee bar antwerpen ",
                "00099",                                       // different statement number
                42);                                           // different line in the file

        assertThat(detector.contentHash(reformatted)).isEqualTo(detector.contentHash(base));
    }

    @Test
    void counterpartyAccountIgnoresSpacesAndCase() {
        Transaction plain = new Transaction(DAY, DAY, Money.of("-1", "EUR"), null, null,
                "BE00999900000001", "X", null, 1);
        Transaction spaced = new Transaction(DAY, DAY, Money.of("-1", "EUR"), null, null,
                "be00 9999 0000 0001", "X", null, 1);

        assertThat(detector.contentHash(spaced)).isEqualTo(detector.contentHash(plain));
    }

    @Test
    void hashFitsTheDatabaseColumn() {
        // transactions.dedup_key is CHAR(64) with CHECK (dedup_key ~ '^[0-9a-f]{64}$')
        assertThat(detector.contentHash(coffee(1))).matches("[0-9a-f]{64}");
    }

    // ---------- helpers ----------

    private static Transaction coffee(int sourceRow) {
        return new Transaction(DAY, DAY, Money.of("-3.50", "EUR"), null, null, null,
                "PAYMENT VIA DEBIT CARD NUMBER 9999 99XX XXXX 0001 06/01 FAKE COFFEE BAR ANTWERPEN",
                "00001", sourceRow);
    }

    private static Transaction with(Transaction t, LocalDate booking, LocalDate value, String amount, String description) {
        return new Transaction(booking, value, Money.of(amount, "EUR"), null, t.counterpartyName(),
                t.counterpartyAccount(), description, t.statementNumber(), t.sourceRow());
    }

    /** Stands in for the database: what would be stored after importing these. */
    private static Set<DuplicateKey> store(List<KeyedTransaction> imported) {
        return imported.stream().map(KeyedTransaction::key).collect(Collectors.toCollection(HashSet::new));
    }

    private static List<Transaction> parseFixture(KbcCsvParser parser, String name) throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("fixtures", "kbc", name))) {
            return parser.parse(in).transactions();
        }
    }
}

package io.github.benjaminursu.bankimport.realdata;

import io.github.benjaminursu.bankimport.model.Money;
import io.github.benjaminursu.bankimport.model.Transaction;
import io.github.benjaminursu.bankimport.parser.BankFileFormatException;
import io.github.benjaminursu.bankimport.parser.KbcCsvParser;
import io.github.benjaminursu.bankimport.parser.ParsedStatement;
import io.github.benjaminursu.bankimport.service.DuplicateDetector;
import io.github.benjaminursu.bankimport.service.DuplicateDetector.KeyedTransaction;
import io.github.benjaminursu.bankimport.service.DuplicateDetector.Result;
import io.github.benjaminursu.bankimport.service.DuplicateKey;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the KBC parser and the duplicate detector on <em>real</em> exports that live outside the
 * repo, and prints only counts. Local only: it never runs in normal builds or CI, because
 * <ul>
 *   <li>the "realdata" tag is excluded by default in pom.xml, and</li>
 *   <li>it is skipped unless BANK_SAMPLES_DIR is set.</li>
 * </ul>
 * Run it (PowerShell):
 * <pre>
 * $env:BANK_SAMPLES_DIR = "D:\bank-samples"
 * .\mvnw.cmd test "-Dgroups=realdata" "-DexcludedTags="
 * </pre>
 * Privacy rules for this class: print counts, dates, pass/fail only. Never a description, name,
 * IBAN, amount or balance, and not even the file name (export names can contain the account
 * number). Parser errors are printed because {@link BankFileFormatException} messages contain only
 * a line number and the kind of problem; any other exception prints only its class name.
 */
@Tag("realdata")
@EnabledIfEnvironmentVariable(named = "BANK_SAMPLES_DIR", matches = ".+")
class KbcRealDataTest {

    private final KbcCsvParser parser = new KbcCsvParser();
    private final DuplicateDetector detector = new DuplicateDetector();

    @Test
    void parsesEveryRealKbcExport() throws Exception {
        Path dir = Path.of(System.getenv("BANK_SAMPLES_DIR"), "kbc");
        assertThat(dir).as("BANK_SAMPLES_DIR\\kbc").isDirectory();

        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .sorted()
                    .toList();
        }
        assertThat(files).as("CSV files in BANK_SAMPLES_DIR\\kbc").isNotEmpty();

        List<String> failures = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            String label = "file " + (i + 1) + " of " + files.size();
            System.out.println();
            System.out.println("== KBC " + label);
            try {
                checkOneFile(files.get(i), label, failures);
            } catch (BankFileFormatException e) {
                System.out.println("   parse:            FAILED (" + e.getMessage() + ")");
                failures.add(label + ": parse failed");
            } catch (Exception e) {
                System.out.println("   parse:            FAILED (" + e.getClass().getSimpleName() + ")");
                failures.add(label + ": " + e.getClass().getSimpleName());
            }
        }
        System.out.println();

        assertThat(failures).as("files with problems (details printed above)").isEmpty();
    }

    private void checkOneFile(Path file, String label, List<String> failures) throws Exception {
        ParsedStatement statement;
        try (InputStream in = Files.newInputStream(file)) {
            statement = parser.parse(in);
        }
        List<Transaction> rows = statement.transactions();

        LocalDate first = rows.stream().map(Transaction::bookingDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate last = rows.stream().map(Transaction::bookingDate).max(Comparator.naturalOrder()).orElseThrow();

        // The parser already checked every link of the chain (it would have thrown otherwise).
        // This is the end-to-end version: opening + all amounts = closing.
        Money sum = statement.openingBalance();
        for (Transaction t : rows) {
            sum = sum.plus(t.amount());
        }
        boolean balanceOk = sum.equals(statement.closingBalance());

        // Import into an empty store, then import the same file again.
        Result firstImport = detector.check(rows, Set.of());
        Set<DuplicateKey> stored = firstImport.toImport().stream()
                .map(KeyedTransaction::key)
                .collect(Collectors.toSet());
        Result secondImport = detector.check(rows, stored);
        long identicalLooking = firstImport.toImport().stream()
                .filter(k -> k.key().occurrence() > 1)
                .count();

        System.out.println("   parse:            ok");
        System.out.println("   rows:             " + rows.size());
        System.out.println("   currency:         " + statement.currency().getCurrencyCode());
        System.out.println("   booking dates:    " + first + " .. " + last);
        System.out.println("   balance check:    " + (balanceOk ? "passed" : "FAILED")
                + " (opening + amounts = closing)");
        System.out.println("   1st import:       " + firstImport.toImport().size() + " new, "
                + firstImport.duplicates().size() + " duplicates");
        System.out.println("   identical-looking rows kept (occurrence > 1): " + identicalLooking);
        System.out.println("   2nd import:       " + secondImport.toImport().size() + " new, "
                + secondImport.duplicates().size() + " duplicates (expected 0 new, " + rows.size() + " duplicates)");

        if (!balanceOk) {
            failures.add(label + ": balance check failed");
        }
        if (firstImport.toImport().size() != rows.size()) {
            failures.add(label + ": first import did not keep every row");
        }
        if (!secondImport.toImport().isEmpty() || secondImport.duplicates().size() != rows.size()) {
            failures.add(label + ": second import was not all duplicates");
        }
    }
}

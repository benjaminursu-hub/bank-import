package io.github.benjaminursu.bankimport.db;

import io.github.benjaminursu.bankimport.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real Flyway migrations against a real PostgreSQL 18 (Testcontainers)
 * and checks that the constraints reject bad data. Plain SQL on purpose: this
 * tests the schema, not JPA.
 */
@Tag("docker")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationTest {

    private static final String KEY_A = "a".repeat(64);
    private static final String KEY_B = "b".repeat(64);
    private static final String SHA_1 = "1".repeat(64);
    private static final String SHA_2 = "2".repeat(64);

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE transactions, import_batches, accounts RESTART IDENTITY CASCADE");
    }

    @Test
    void migrationV1IsApplied() {
        String version = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(version).isEqualTo("1");
    }

    @Test
    void storesAmountsExactly() {
        long account = insertAccount("BE00000000000001", "EUR");
        long batch = insertBatch(account, SHA_1);
        insertTransaction(account, batch, "-18.99", "EUR", KEY_A, 1);

        BigDecimal amount = jdbc.queryForObject("SELECT amount FROM transactions", BigDecimal.class);
        assertThat(amount).isEqualByComparingTo("-18.99");
    }

    @Test
    void rejectsLowerCaseOrWrongLengthCurrency() {
        assertThatThrownBy(() -> insertAccount("BE00000000000001", "eur"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAccount("BE00000000000001", "EU"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSameAccountTwice() {
        insertAccount("BE00000000000001", "EUR");
        assertThatThrownBy(() -> insertAccount("BE00000000000001", "EUR"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSameFileTwiceForOneAccount() {
        long account = insertAccount("BE00000000000001", "EUR");
        insertBatch(account, SHA_1);
        assertThatThrownBy(() -> insertBatch(account, SHA_1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameKeyIsAllowedWithDifferentOccurrence() {
        // Two real, identical-looking payments: same key, occurrence 1 and 2.
        long account = insertAccount("BE00000000000001", "EUR");
        long batch = insertBatch(account, SHA_1);
        insertTransaction(account, batch, "-3.50", "EUR", KEY_A, 1);
        insertTransaction(account, batch, "-3.50", "EUR", KEY_A, 2);

        Integer count = jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void rejectsSameKeyAndOccurrenceOnOneAccount() {
        long account = insertAccount("BE00000000000001", "EUR");
        long batch1 = insertBatch(account, SHA_1);
        long batch2 = insertBatch(account, SHA_2);
        insertTransaction(account, batch1, "-3.50", "EUR", KEY_A, 1);

        assertThatThrownBy(() -> insertTransaction(account, batch2, "-3.50", "EUR", KEY_A, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameKeyIsAllowedOnDifferentAccounts() {
        long account1 = insertAccount("BE00000000000001", "EUR");
        long account2 = insertAccount("BE00000000000002", "EUR");
        insertTransaction(account1, insertBatch(account1, SHA_1), "-3.50", "EUR", KEY_A, 1);
        insertTransaction(account2, insertBatch(account2, SHA_1), "-3.50", "EUR", KEY_A, 1);

        Integer count = jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void rejectsTransactionInBatchOfAnotherAccount() {
        long account1 = insertAccount("BE00000000000001", "EUR");
        long account2 = insertAccount("BE00000000000002", "EUR");
        long batchOfAccount2 = insertBatch(account2, SHA_1);

        assertThatThrownBy(() -> insertTransaction(account1, batchOfAccount2, "-3.50", "EUR", KEY_A, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsOccurrenceZeroAndMalformedKey() {
        long account = insertAccount("BE00000000000001", "EUR");
        long batch = insertBatch(account, SHA_1);
        assertThatThrownBy(() -> insertTransaction(account, batch, "-3.50", "EUR", KEY_A, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTransaction(account, batch, "-3.50", "EUR", "not-a-hash", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingBatchDeletesItsTransactionsButAccountCannotBeDeletedWhileInUse() {
        long account = insertAccount("BE00000000000001", "EUR");
        long batch = insertBatch(account, SHA_1);
        insertTransaction(account, batch, "-3.50", "EUR", KEY_A, 1);
        insertTransaction(account, batch, "12.00", "EUR", KEY_B, 1);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM accounts WHERE id = ?", account))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM import_batches WHERE id = ?", batch);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void rejectsImpossibleBatchCounts() {
        long account = insertAccount("BE00000000000001", "EUR");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO import_batches (account_id, parser, file_sha256, rows_read, rows_imported, rows_duplicate)
                VALUES (?, 'kbc-csv', ?, 5, 4, 2)""", account, SHA_1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertAccount(String number, String currency) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (bank, account_number, currency) VALUES ('KBC', ?, ?) RETURNING id",
                Long.class, number, currency);
    }

    private long insertBatch(long accountId, String sha) {
        return jdbc.queryForObject(
                "INSERT INTO import_batches (account_id, parser, file_sha256) VALUES (?, 'kbc-csv', ?) RETURNING id",
                Long.class, accountId, sha);
    }

    private void insertTransaction(long accountId, long batchId, String amount, String currency,
                                   String key, int occurrence) {
        jdbc.update("""
                INSERT INTO transactions
                    (account_id, import_batch_id, booking_date, amount, currency, source_row, dedup_key, occurrence)
                VALUES (?, ?, DATE '2026-01-15', ?, ?, 1, ?, ?)""",
                accountId, batchId, new BigDecimal(amount), currency, key, occurrence);
    }
}

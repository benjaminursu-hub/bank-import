-- V1: the three core tables.
-- Never edit this file after it has run anywhere: Flyway stores a checksum.
-- Changes go in a new file (V2__..., V3__...).

-- ---------------------------------------------------------------------------
-- accounts: one row per bank account the user imports from.
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    -- Surrogate key. GENERATED ALWAYS (not BY DEFAULT) means nobody can insert
    -- their own id by accident; the database always picks it.
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Which bank format this account comes from, e.g. 'KBC'. Not an enum type:
    -- adding a bank later should not need an ALTER TYPE migration.
    bank           TEXT        NOT NULL CHECK (bank <> ''),

    -- The account number exactly as the bank writes it (IBAN for KBC, but maib
    -- card accounts are not always IBANs, so no IBAN format check here).
    account_number TEXT        NOT NULL CHECK (account_number <> ''),

    -- ISO 4217 code: exactly three upper-case letters. Nothing assumes EUR.
    currency       CHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),

    display_name   TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The same account cannot be registered twice for the same bank.
    CONSTRAINT uq_accounts_bank_number UNIQUE (bank, account_number)
);

-- ---------------------------------------------------------------------------
-- import_batches: one row per uploaded file. Every transaction remembers which
-- file it came from, so a bad import can be inspected or undone as a unit.
-- ---------------------------------------------------------------------------
CREATE TABLE import_batches (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- ON DELETE RESTRICT (the default, written out on purpose): you cannot
    -- delete an account that still has imports. Deleting history must be a
    -- deliberate step, not a side effect.
    account_id      BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,

    -- Name of the parser that read the file, e.g. 'kbc-csv'.
    parser          TEXT        NOT NULL CHECK (parser <> ''),

    -- Original file name, for the user's information only (never trusted).
    source_filename TEXT,

    -- SHA-256 of the file bytes as 64 lower-case hex characters.
    file_sha256     CHAR(64)    NOT NULL CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),

    -- Counts filled in by the import: rows read, rows stored, rows skipped as
    -- duplicates. Counts can never be negative, and stored + skipped can never
    -- exceed what was read.
    rows_read       INTEGER     NOT NULL DEFAULT 0 CHECK (rows_read >= 0),
    rows_imported   INTEGER     NOT NULL DEFAULT 0 CHECK (rows_imported >= 0),
    rows_duplicate  INTEGER     NOT NULL DEFAULT 0 CHECK (rows_duplicate >= 0),
    CONSTRAINT ck_import_batches_counts CHECK (rows_imported + rows_duplicate <= rows_read),

    imported_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Uploading the exact same file twice for the same account is refused
    -- outright. (Overlapping but different files are handled per transaction
    -- by the duplicate key below.)
    CONSTRAINT uq_import_batches_file UNIQUE (account_id, file_sha256),

    -- id alone is already unique; this extra pair exists only so that
    -- transactions can point at (id, account_id) together, see below.
    CONSTRAINT uq_import_batches_id_account UNIQUE (id, account_id)
);

CREATE INDEX ix_import_batches_account ON import_batches (account_id);

-- ---------------------------------------------------------------------------
-- transactions: one row per booked movement on an account.
-- ---------------------------------------------------------------------------
CREATE TABLE transactions (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    account_id           BIGINT        NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,

    import_batch_id      BIGINT        NOT NULL,

    -- Booking date is always present. Value date is the date interest counts
    -- from; KBC always fills it, other banks may not, so it is nullable.
    booking_date         DATE          NOT NULL,
    value_date           DATE,

    -- Money: NUMERIC is exact decimal (no floating-point rounding).
    -- (19,4): 15 digits before the point, 4 after. Bank amounts use 2 decimals,
    -- but some currencies use 3, and 4 leaves room without losing anything.
    amount               NUMERIC(19,4) NOT NULL,
    currency             CHAR(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),

    -- Account balance right after this transaction, as reported by the bank.
    -- Nullable: maib "pending" rows have no balance yet.
    balance_after        NUMERIC(19,4),

    counterparty_name    TEXT,
    counterparty_account TEXT,

    -- Full free text from the bank. Empty string allowed, NULL not: "no text"
    -- and "unknown" mean the same thing here, so only one representation.
    description          TEXT          NOT NULL DEFAULT '',

    -- KBC 'Statement number'. NOT unique: several rows share one statement.
    statement_number     TEXT,

    -- 1-based position of the row in the source file, so a problem can be
    -- reported as "row 12" without printing any of the row's contents.
    source_row           INTEGER       NOT NULL CHECK (source_row >= 1),

    -- Duplicate detection (see service/DuplicateDetector):
    -- dedup_key  = SHA-256 hex of the transaction's content (dates, amount,
    --              currency, counterparty, description).
    -- occurrence = 1 for the first row with that key in a file, 2 for the
    --              second identical-looking row in the same file, and so on.
    -- Two real identical payments on the same day get occurrence 1 and 2, so
    -- they stay two rows. Re-importing an overlapping file produces the same
    -- (key, occurrence) pairs again, and those are skipped.
    dedup_key            CHAR(64)      NOT NULL CHECK (dedup_key ~ '^[0-9a-f]{64}$'),
    occurrence           INTEGER       NOT NULL CHECK (occurrence >= 1),

    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- The safety net under the Java duplicate check: even if the application
    -- has a bug, the database refuses to store the same (key, occurrence) twice
    -- on one account.
    CONSTRAINT uq_transactions_dedup UNIQUE (account_id, dedup_key, occurrence),

    -- Composite foreign key: the batch must exist AND belong to the same
    -- account as the transaction. A plain FK on import_batch_id would allow a
    -- transaction of account 1 inside a batch of account 2.
    -- ON DELETE CASCADE: deleting a batch deletes the transactions it created;
    -- this is how a bad import is undone.
    CONSTRAINT fk_transactions_batch FOREIGN KEY (import_batch_id, account_id)
        REFERENCES import_batches (id, account_id) ON DELETE CASCADE
);

-- Most queries are "transactions of account X in date range Y".
CREATE INDEX ix_transactions_account_date ON transactions (account_id, booking_date);

-- Foreign keys are not indexed automatically in PostgreSQL. Without this index,
-- deleting a batch (ON DELETE CASCADE) would scan the whole table.
CREATE INDEX ix_transactions_batch ON transactions (import_batch_id);

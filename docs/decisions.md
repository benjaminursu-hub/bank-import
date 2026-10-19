# Design decisions

Why the foundation (branch `build/foundation`) looks the way it does. Read it next to
`git log main..build/foundation` and the code. When a decision changes, update this file in the
same commit.

## What was built

| Commit | What |
|---|---|
| `main`: Initial Spring Boot skeleton | Own repo inside `bank-import` (`git init -b main`), separate from the parent folder's repo. `.claude/settings.local.json` in `.gitignore`. `mvnw` committed as executable (`100755`) so Linux CI can run it. |
| CI | `.github/workflows/ci.yml`: Ubuntu + Windows matrix, Temurin 21, `mvnw verify`. |
| open-in-view | `spring.jpa.open-in-view=false` |
| Postgres 18 | Testcontainers image `postgres:latest` → `postgres:18` |
| V1 migration | `accounts`, `import_batches`, `transactions` + `FlywayMigrationTest` (12 tests) |
| Fixtures | `fixtures/kbc/*.csv` (4 files, invented data) + `fixtures/README.md` |
| Model | `Money`, `Transaction` |
| Parser | `BankParser`, `ParsedStatement`, `BankFileFormatException`, `KbcCsvParser` + 31 tests |
| Duplicates | `DuplicateKey`, `DuplicateDetector` + 8 tests |
| Real KBC header | Parser and fixtures switched to the exact header of a real export; Debit must be signed |

52 tests. With `-DexcludedGroups=docker` (what the Windows CI job runs) the Docker tests are skipped.

## Git / CI
- **Docker tests are tagged `@Tag("docker")` and skipped on Windows CI.** GitHub's
  `windows-latest` runners only run *Windows* containers; `postgres:18` is a Linux image, so it
  cannot start there. Ubuntu runs everything; Windows still runs the parser and duplicate tests,
  which is where Windows-specific bugs (line endings, encodings, paths) would show up anyway.
  The `contextLoads` test is tagged too, because it also starts a container.
- **`fixtures/** -text` in `.gitattributes`.** With `core.autocrlf=true`, git could rewrite line
  endings in the fixtures and the CR-only quirk would disappear, so the tests would silently stop
  testing it. The parser tests also check every fixture's bytes (real header, CR after every line).
- **Postgres pinned to 18 in tests.** `latest` would one day become 19 while `compose.yaml`
  runs 18: tests and app on different databases.
- `TestcontainersConfiguration` is `public` so tests in other packages (`db`) can import it.

## Database (`V1__initial_schema.sql`, every constraint commented in the file)
- `NUMERIC(19,4)` + `CHAR(3)` currency with a `^[A-Z]{3}$` check. Exact decimals, never EUR-only.
- `GENERATED ALWAYS AS IDENTITY` rather than `BY DEFAULT`, so nobody inserts ids by hand.
- `UNIQUE (account_id, file_sha256)` on batches: the exact same file cannot be imported twice.
- **`UNIQUE (account_id, dedup_key, occurrence)`**: the database-level safety net for duplicates.
- **Composite FK `(import_batch_id, account_id) → import_batches (id, account_id)`**: a plain FK
  would allow a transaction of account 1 inside a batch of account 2. This needs the extra
  `UNIQUE (id, account_id)` on batches, which looks redundant but is what the FK points at.
- `ON DELETE CASCADE` batch → transactions (undo an import), `RESTRICT` account → anything
  (deleting history must be deliberate).
- Index on `import_batch_id`, because PostgreSQL does not index foreign keys automatically.
- **No JPA entities yet.** Nothing stores data until the upload endpoint (weeks 7–8); entities
  written now would guess at how persistence should work. The schema is tested with plain SQL
  via `JdbcTemplate`.
- Not added yet: transaction currency vs account currency (maib has both). That's a `V2` when maib arrives.

## Model
- `Money` = `BigDecimal` + `java.util.Currency`. `equals` uses `compareTo`, because
  `new BigDecimal("3.5").equals(new BigDecimal("3.50"))` is **false** (scale differs).
- `Transaction` is a record; **its `toString()` is overridden** to print only the row number.
  A record's default `toString` prints every field, so `log.info("{}", tx)` would leak IBANs
  and descriptions. Same for `ParsedStatement`.

## KBC parser
- **The real header**, confirmed from a real export:
  `Accountnumber;Heading;Name;Currency;Statement number;Date;Description;Value date;Amount;Balance;Credit;Debit;Counterparty account number;Counterparty BIC;Counterparty name;Counterparty address;Standard-format reference;Free-format reference`.
  Note `Accountnumber` is one word. `Heading` and `Counterparty address` are always empty in real
  exports; the parser does not read them (nor `Name`, `Counterparty BIC` or the two references).
- **Reads all bytes, then splits on `\r\n|\r|\n`.** That handles CR-only, CRLF and LF. Real
  exports end every line, including the last, with CR; a file without the final CR is still
  accepted. No CSV library: the format is simple, the quirks are the point, and 200 readable lines
  are easy to explain. A small quote-aware splitter is included in case a description contains `;`.
- **Columns found by header name, not position** (case-insensitive). If KBC reorders columns it
  still works; if a name changes it fails on line 1 and lists the missing names.
- **18/19 columns:** a row may have header-size + 1 fields only if the extra one is empty. Any other
  count is refused, because shifted columns would put every value into the wrong field.
- **Amounts:** decimal comma; `.` only as a thousands separator in groups of 3. `1.5` is refused
  because it is ambiguous. (Whether KBC ever writes thousands separators is still open.)
- **Dates:** `dd/MM/uuuu` with `ResolverStyle.STRICT`, so `31/02/2026` is an error, not 28 Feb.
- **Credit/Debit cross-check, signed.** Debit is signed in real exports (`-18,99`), so Debit must
  equal Amount exactly, sign included; Credit must equal Amount for money in. An unsigned Debit is
  refused: it would mean we are reading some other column. (Credit is assumed positive; no real
  export has contradicted that.)
- **Balance chain:** previous balance + amount must equal balance, for every row. This catches
  misread decimals, wrong columns, missing rows and newest-first files. Opening and closing
  balance are derived from it.
- **Encoding:** strict UTF-8 first, Windows-1252 if that fails (BOM stripped). Windows-1252 bytes
  for é/ë are invalid UTF-8, so the fallback triggers reliably.
- **Reversals** (`CORRECTING ENTRY`) are ordinary rows and are kept. They are not duplicates: the sign differs.
- **Fail loudly:** any problem throws `BankFileFormatException("line N: what")`. Messages never
  contain row contents (there's a test for that). A half-imported file is worse than a refused one.
- **A header-only file is refused** ("no transactions"). Decided: keep it that way. There is no
  account number to attach an empty import to.

## Duplicate detection (the most important part to understand)
Key = **(SHA-256 of content, occurrence)**. The occurrence counts how often that hash has already
appeared *earlier in the same file*.

```
file A:  coffee 06/01 -3,50   → (h, 1)   stored
         coffee 06/01 -3,50   → (h, 2)   stored     ← two real coffees, both kept
file B:  coffee 06/01 -3,50   → (h, 1)   already stored → skip
         coffee 06/01 -3,50   → (h, 2)   already stored → skip
         coffee 06/01 -3,50   → (h, 3)   new → stored   ← a third real coffee later that day
```

- Hash includes: booking date, value date, amount (`stripTrailingZeros`), currency, counterparty
  account, description (whitespace collapsed, upper-cased).
- Hash **excludes** balance, statement number, source row and counterparty name; the reasons are in
  the javadoc of `contentHash`. Balance would actually make identical payments unique on KBC, but
  if the bank lists same-day rows in a different order in a later export, the balances shift and
  everything that day would be imported twice.
- `DuplicateDetector` is pure logic: it gets the already-stored keys and splits new vs duplicate.
  The DB unique constraint is the second line of defence.
- The tests guard the rule: with the counter replaced by a constant `1` (the naive "same content =
  duplicate" approach), 4 of the 8 duplicate tests fail.
- **Known limit:** it assumes an export contains *all* transactions of each day it covers, in the
  bank's order. If an export could contain the 2nd coffee of a day but not the 1st, that 2nd coffee
  would be counted as occurrence 1 and wrongly skipped. Date-range exports can't do that (open question 4).

## Real-data check (local only)
`realdata/KbcRealDataTest` parses every `.csv` in `%BANK_SAMPLES_DIR%\kbc` (outside the repo) and
prints only counts, dates and pass/fail. Two independent guards keep it out of normal builds and CI:
the `realdata` tag is excluded by default (`<excludedTags>` property in `pom.xml`), and the test is
skipped unless `BANK_SAMPLES_DIR` is set. It prints "file 1 of N", never file names (an export's
name can contain the account number), and for unexpected exceptions only the class name.

```
$env:BANK_SAMPLES_DIR = "D:\bank-samples"
.\mvnw.cmd test "-Dgroups=realdata" "-DexcludedTags="
```

The exclusion is a property, not a fixed `<excludedGroups>`, because a fixed value in the pom
would silently override `-DexcludedGroups=docker` on the command line, and the Windows CI job
would run the Docker tests again. CI now passes `-DexcludedTags=realdata,docker`.

## What to study to own this code
1. **Flyway:** versioned migrations, why you never edit an applied migration (checksums).
2. **PostgreSQL constraints:** `CHECK`, `UNIQUE`, composite foreign keys, `ON DELETE` options,
   identity columns, `NUMERIC(p,s)` vs `double precision`, why FKs need manual indexes.
3. **`BigDecimal`:** `equals` vs `compareTo`, `stripTrailingZeros`, scale.
4. **Java records:** compact constructors, overriding `toString`/`equals`.
5. **Character encodings:** bytes vs chars, UTF-8 vs Windows-1252, `CharsetDecoder` with
   `CodingErrorAction.REPORT`, what a BOM is. Open the two accents fixtures in a hex viewer and compare.
6. **Line endings:** CR / LF / CRLF, `core.autocrlf`, `.gitattributes`.
7. **`java.time`:** `uuuu` vs `yyyy`, `ResolverStyle.STRICT`.
8. **Regex:** read the `AMOUNT` pattern and explain which inputs it accepts.
9. **Hashing:** what SHA-256 gives you and why fields are joined with a separator character.
10. **Testing:** JUnit 5 `@Tag`, AssertJ, Testcontainers + `@ServiceConnection`, and how a
    "mutation check" (break the code on purpose, see the test fail) proves a test is useful.
11. **GitHub Actions:** matrix builds, `runner.os`, why Windows runners can't run Linux containers.
12. **Open Session In View:** what it does and why it's turned off.

Good exercise: delete one check (e.g. the balance chain) and predict which tests fail before running them.

## Answered
- Real KBC header: see "KBC parser" above.
- Debit is signed (`-18,99`).
- Every line, including the last, ends with CR.
- `Heading` and `Counterparty address` are empty in real exports.
- Header-only file: keep refusing it.
- This file: lives at `docs/decisions.md` and is committed.

## Open questions
1. **Encoding.** Open a real export containing é/ë in a hex viewer: `C3 A9` = UTF-8,
   `E9` = Windows-1252. Then pick one and drop the fallback, or keep it.
2. **Amounts ≥ 1000:** `1500,00` or `1.500,00`? Both are accepted today.
3. **Recent rows:** when an export includes today/yesterday, do those rows already have a statement
   number, and does their description change later? (If the description changes, the same payment
   would get a new hash and be imported twice.)
4. **Could an export ever start or end in the middle of a day?** (See the known limit above.)
5. **Nested repo:** `bank-import` has its own `.git` inside the parent folder's repo, which shows
   `portfolio/bank-import/` as an untracked nested repo. Should the parent ignore it?

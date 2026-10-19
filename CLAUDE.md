# bank-import

A free, self-hosted service that imports bank exports (first Belgian, later Moldovan banks),
cleans them, removes duplicates, applies the user's rules, and exports them to budgeting apps
(Firefly III via its REST API, Actual Budget via CSV). Runs locally with `docker compose up`;
no cloud, no paid APIs, no AI.

Owner: Benjamin Ursu, Applied Computer Science student (KdG, Antwerp). This is a portfolio
project and a learning project. He must be able to explain every line.

## How to work with Benjamin (most important section)

- **Coach, don't take over.** He learns by doing. Default to: explain the next small step,
  say *why*, point to where the code belongs, let him write it, then review it.
- Write complete code only when he explicitly asks for it ("write it", "do it for me").
  Even then, explain what you wrote and why.
- One clear next action at a time. Don't dump large answers.
- Build on his code instead of replacing it with your own version.
- Be honest: disagree when he's wrong and explain clearly. Don't just agree.
- He writes casually and with typos; focus on meaning.
- **Git:** he is the only contributor. Never add `Co-Authored-By` or other AI trailers to
  commits. Don't commit or push unless he asks.

## Stack

Java 21 · Spring Boot 4.1.1 · Maven · PostgreSQL 18 (Docker Compose, `compose.yaml`) ·
Flyway (SQL migrations in `src/main/resources/db/migration`) · Spring Data JPA plus
hand-written SQL where it matters · JUnit 5 · Testcontainers · GitHub Actions (planned:
Windows + Ubuntu).

## Commands (Windows, PowerShell)

The system `java` on PATH is Java 8. Maven needs 21, so in a plain terminal set it first:

```
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\ms-21.0.12.1"
.\mvnw.cmd test          # run all tests
.\mvnw.cmd spring-boot:run
```

Docker Desktop must be running ("Engine running") before starting the app or tests.
Running from IntelliJ uses the project JDK (21) automatically.

## Hard rules

- **No real bank data in the repo, ever.** Real exports live outside the project
  (e.g. `D:\bank-samples\`). Test fixtures in `fixtures/` are hand-made fakes that copy
  the *format* exactly (separators, dates, encoding, quirks) with invented names, IBANs
  and amounts.
- Never log or print transaction contents (descriptions, names, IBANs). Log counts and IDs.
- Money is `BigDecimal` plus a currency code, never `double`. Nothing assumes EUR.
- A new bank format is not done until it has a fixture and tests, including its quirks.
- Duplicate detection must never merge two real, identical-looking payments
  (same day, amount and counterparty happens: both KBC and maib exports contain cases).

## Planned design

- `parser/`: one class per bank format behind one interface → common `Transaction` model
- `model/`: Transaction, Account, Rule, ImportBatch
- `service/`: duplicate detection, rules, import orchestration
- `export/`: Firefly III client, Actual CSV writer
- `api/`: REST controllers + validation
- Use the bank's running balance column to verify parsing (sums must match balances).

## Known bank formats

**KBC (CSV)** — the first bank:
- Separator `;`, decimal comma (`-18,99`), dates `dd/MM/yyyy`
- **Line endings are CR only** (no LF): many CSV readers see one giant line
- Header has 18 columns, but **every data row has 19** (trailing `;`)
- Two dates: booking `Date` and `Value date` (often different)
- `Amount` signed, plus redundant `Credit` / `Debit` columns (use as a cross-check)
- `Balance` after each row; rows are oldest first and the chain adds up
- No unique transaction ID; `Statement number` is shared by several rows
- Description is one long text containing card, time, merchant and city
- Includes reversals ("CORRECTING ENTRY ...")
- Encoding not yet confirmed (sample was pure ASCII); test with é/ë

**maib, Moldova (PDF only for personal accounts)**: later, weeks 17–19:
- Sections: account transfers (`#Cont`), card payments (`#Cardul`), and pending
  ("Tranzacții în procesare": no processing date, no balance)
- Two dates (transaction / processing), amount in transaction currency and account currency
- Fee column, balance after each transaction, ISO dates, decimal point
- Victoriabank and MICB to follow (also PDF)

## Roadmap (short)

1. Weeks 1–2: skeleton, Docker Compose, CI ← current
2. Weeks 3–4: schema + Flyway migrations, SQL by hand
3. Weeks 5–6: KBC parser, `Transaction` model, duplicate key
4. Weeks 7–8: bank 2 (Belfius), upload endpoint, review page, Actual CSV export → v0.1
5. Later: auth, Firefly client, background jobs, maib (PDF), logging, v1.0

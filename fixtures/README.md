# Test fixtures

**Everything here is invented.** Names, IBANs, card numbers, addresses and amounts are fake.
Only the *format* copies the real bank exports. Never put a real export in this folder.

`.gitattributes` marks `fixtures/**` as `-text`, so git never rewrites line endings or
encodings. Do not open and re-save these files in an editor: most editors will
"fix" the CR-only line endings and the quirk under test is gone.

## KBC (`kbc/`)

Every KBC file copies these quirks:

- the exact real header (column names copied from a real export):
  `Accountnumber;Heading;Name;Currency;Statement number;Date;Description;Value date;Amount;Balance;Credit;Debit;Counterparty account number;Counterparty BIC;Counterparty name;Counterparty address;Standard-format reference;Free-format reference`
- `Heading` and `Counterparty address` are always empty, as in real exports
- `;` separator, decimal comma (`-18,99`), dates `dd/MM/yyyy`
- **CR-only line endings** (byte 13, no byte 10), and every line ends with CR, including the last
- header has 18 columns, every data row has 19 (trailing `;`)
- booking `Date` and `Value date` differ on some rows
- signed `Amount` plus redundant `Credit` / `Debit` columns; `Debit` is signed too (`-18,99`)
- `Balance` after each row, oldest row first, the chain adds up
- `Statement number` shared by several rows, no unique ID

| File | Rows | What it covers |
|------|------|----------------|
| `kbc-basic.csv` | 8 | salary credit, card payments with value date ≠ booking date, **two identical coffees on the same day** (must both be kept), an online purchase and its `CORRECTING ENTRY` reversal, transfers with a standard-format reference. Opening balance 500,00. |
| `kbc-overlap.csv` | 8 | A later export of the same account: first 6 rows repeat rows 3–8 of `kbc-basic.csv` (including both coffees), then 2 new rows (one is another coffee on a different day). Importing it after `kbc-basic.csv` must add exactly 2. |
| `kbc-accents-utf8.csv` | 2 | é / ë / è in description and counterparty, UTF-8 (no BOM) |
| `kbc-accents-windows1252.csv` | 2 | the same content in Windows-1252, in case that is what KBC really uses |

The real KBC encoding is not confirmed yet (see `docs/decisions.md`); the parser accepts both.

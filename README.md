# Chegg POS

A single-terminal desktop point of sale for a small retail shop in Kenya.
Swing UI, Spring Framework 7 (no Spring Boot), SQLite, PDF receipts and
role-specific workspaces for system admin, store admin, cashier and customer
kiosk.

- Currency: **KES** (`KSh 1,250.00`), all money held as integer cents.
- **No VAT** — the tax engine is present but disabled (`tax.enabled=false`).
- Cash is rounded to the nearest KES 1.00; card payments are exact.
- Receipts and Z-reports are A5/A4 PDFs.
- Timestamps are stored in UTC and displayed in Africa/Nairobi (EAT).

## Requirements

- JDK **25** or newer (the build targets Java 25).
- Maven 3.9+.
- A desktop session for the UI. `--smoke-test` runs without a display.
- Optional, for installers: `jpackage` (bundled with the JDK), `dpkg-deb`
  on Linux, WiX Toolset 3 on Windows.

## Quick start

```bash
# Run the test suite, build the shaded jar and check the database bootstrap.
mvn verify
scripts/smoke-test.sh

# Start the application.
scripts/run.sh
```

Or build and run directly:

```bash
mvn -DskipTests package
java -jar target/chegg-pos.jar
```

The first run creates the database, applies the schema migration and seeds
roles, permissions, users, categories and 36 sample products.

### Seeded accounts

| Username     | Password    | Role             |
|--------------|-------------|------------------|
| `sysadmin`   | `change-me` | System Admin     |
| `storeadmin` | `change-me` | Store Admin      |
| `cashier1`   | `change-me` | Cashier          |
| `kiosk`      | —           | Customer Kiosk   |

Every seeded staff account must change its password at first sign-in. The
kiosk account has no password and starts in device mode from the login screen.
The staff-exit PIN for the kiosk defaults to `2468`; change it under
**System → Settings** (`kiosk.staffPin`).

## Using the till

1. Sign in as a cashier (or admin).
2. Open the till with the opening cash amount.
3. Search or tap a product, adjust quantities, then press **F4** to tender.
4. Take cash and/or card, confirm, and print or save the receipt.
5. **My Shift** previews the Z-report; closing the shift records counted cash
   and the variance.

Kiosk orders appear in **Kiosk Orders (F9)**. Selecting one hydrates the cart;
checkout completes the order and the sale in one transaction.

Keyboard shortcuts on the checkout screen: `F4` tender, `F9` kiosk queue,
`Enter` add/confirm, `Esc` clear.

## Where data lives

All writable files live outside the installation directory:

| Platform | Default home |
|---|---|
| Linux | `${XDG_DATA_HOME:-~/.local/share}/chegg-pos/` |
| Windows | `%LOCALAPPDATA%\CheggPOS\` |

Override with `-Dpos.home=/custom/path`. The folder contains:

```
pos.db        SQLite database (WAL mode)
receipts/     PDF receipts and Z-reports, by year/month
backups/      database backups (retention from settings)
logs/         application log
exports/      CSV exports
```

Backups are copied from the live database after a WAL checkpoint. Restore by
stopping the app and copying a backup over `pos.db` (remove `pos.db-wal` and
`pos.db-shm` first). Backups older than `backup.retentionDays` (default 14)
are pruned automatically.

## Architecture

```
com.chegg.pos
├── Main                 entry point and --smoke-test
├── app                  Spring config, paths, theme, fonts
├── db                   schema migrations, seed data
├── model                records and enums
├── service              auth, catalog, inventory, cart, checkout, refunds,
│                        shifts, Z-reports, reports, receipts, users, backups
│   └── calc             pure pricing: Money, CartCalculator, TenderCalculator
├── ui                   login, shell, shared widgets
│   └── workspace        admin, cashier, kiosk
└── util                 money parsing/formatting
```

Engineering rules that the code relies on:

- Money is `long` cents; `BigDecimal` only at parse/format boundaries.
- `CartCalculator`, `TenderCalculator` and `Money` are pure and contain all
  arithmetic. Largest-remainder allocation keeps discounts summing exactly.
- Database calls run on a dedicated executor, never on the Swing EDT.
- A checkout is one transaction: stock decrement, sale, items, payments, stock
  movements and audit commit or roll back together.
- Permissions are enforced in services, not only by hiding buttons.
- Audit rows are append-only; security failures use a separate transaction.

## Tests

```bash
mvn test                # unit + integration tests against throwaway SQLite databases
mvn verify              # build, tests, shaded jar
scripts/smoke-test.sh   # seed a fresh database and verify it without a display
scripts/ui-smoke-test.sh  # construct all four workspaces (needs a display)
```

Coverage includes money parsing, cart/discount invariants, tender rounding and
split payments, database bootstrap, checkout commit/rollback, refunds and
voids with restocking, shift close/Z-report reconciliation and kiosk order
completion.

## Packaging

The universal artifact is a shaded jar that runs on any Java 25+ desktop:

```bash
scripts/package-portable.sh     # dist/chegg-pos.jar + launcher
```

Native installers are produced with `jpackage`, which **cannot cross-compile**.
Build each platform on that platform (or in the CI matrix):

```bash
# Linux — app image, .tar.gz and .deb (when dpkg-deb is installed)
scripts/package-linux.sh

# Windows — app image, .zip and .msi (when WiX Toolset 3 is installed)
powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1
```

Some distribution JDKs ship a modified `java.security`, which makes `jlink`
refuse to build a runtime. Point the script at a stock JDK if that happens:

```bash
JPACKAGE=/path/to/jdk-25/bin/jpackage scripts/package-linux.sh
```

Outputs land in `dist/`. The app image bundles a trimmed runtime, so the till
does not need Java installed. The runtime modules are
`java.base`, `java.desktop`, `java.logging`, `java.management`, `java.naming`,
`java.sql`, `java.prefs` and `jdk.unsupported`.

The GitHub Actions workflow in `.github/workflows/build.yml` runs `mvn verify`
on Linux, builds the Linux artifacts, and builds the Windows artifacts on a
Windows runner.

## Troubleshooting

- **No graphical display** — start the app from a desktop session, or run
  `java -jar target/chegg-pos.jar --smoke-test` to verify the database only.
- **“Open the till before taking payments.”** — a sale always belongs to an
  open shift; open the till first.
- **Account locked** — five failed sign-ins lock an account for 15 minutes.
  An administrator can clear `locked_until` in the database if needed.
- **Receipt printer not found** — printing uses the system print dialog and
  falls back to the desktop's print action. The PDF is always saved under
  `receipts/`, so it can be printed later from Sales History.
- **Logs** — see `logs/` under the data home; log level is configured in
  `src/main/resources/logback.xml`.

# OpenCivitas

OpenCivitas is an open-source city roleplay platform for Paper 1.21.11. It is a
clean-room implementation of publicly documented civic roleplay mechanics:
player government, elections, law, careers, businesses, property, healthcare,
and a player-driven economy.

This project is not affiliated with or endorsed by DemocracyCraft. It does not
include DemocracyCraft source code, branding, maps, configuration, written
content, or other proprietary assets. Server owners must supply their own world,
rules, names, balancing, and artwork.

## Current status

The first playable foundation includes:

- Paper 1.21.11 and Java 21
- MiniMessage for every player-facing message
- English and Traditional Chinese message catalogs with per-player locale choice
- SQLite persistence with an auditable per-account ledger
- Configurable starting balance (default `$1,200`)
- `/balance`, `/pay`, `/transactions`, `/about`, and `/locale`
- persistent qualifications and job membership
- two-trade, one-profession, and unlimited government-role limits
- self-service career enrollment plus administrative appointments
- localized, configurable university exams with auditable attempts
- qualification awards and an asynchronous `/university` warp
- entrepreneur-gated firms with separate accounts and transaction ledgers
- atomic business deposits, withdrawals, payments, and disband refunds
- persistent hiring offers, ranked staff, wages, payroll, and ownership transfer
- persistent sign chest shops for personal or firm accounts
- left-click sales, right-click purchases, shift-click stack transactions,
  stock/capacity checks, item search, owner notices, and sales history
- wilderness claims with a management menu, paid claim-block allowance,
  golden-shovel create/resize selection, trust, transfer, and protection
- registered real-estate plots with purchase, fixed-term rent escrow/refunds,
  titleholders, tenants, trusted builders, search, and region protection
- metadata-safe item auctions with bid escrow, buyouts, outbid refunds,
  scheduled settlement, browse GUI, search, and persistent item claims
- asynchronous database access and atomic transfers

The authoritative implementation status is in [docs/PARITY.md](docs/PARITY.md).

## Build

```bash
./gradlew clean test build
```

Copy `build/libs/opencivitas-0.1.0-SNAPSHOT.jar` to a Paper 1.21.11 server's
`plugins` directory. The SQLite JDBC driver is resolved by Paper from the
`libraries` declaration in `plugin.yml`.

## Configuration

Configuration is generated at `plugins/OpenCivitas/config.yml`. Language files
are generated at `plugins/OpenCivitas/lang/`. MiniMessage tags are supported in
all catalog strings.

## Design rules

- All currency is stored as integer cents; floating-point money is never used.
- Database operations never run on the server tick thread after startup.
- Persistent state changes are transactional and auditable.
- PacketEvents is added only for features that require packet interception or
  client-side illusions. It is not justified for the current command/database
  foundation.

## License

[MIT](LICENSE)

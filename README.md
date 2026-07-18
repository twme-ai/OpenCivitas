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
- `/balance`, `/baltop`, `/pay`, `/transactions`, `/about`, and `/locale`
- persistent qualifications and job membership
- permanent/expiring licenses and verified displayed-job prefix selection
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
- persistent individual block protection with automatic private container locks,
  five use policies, player/group/permission/password ACLs, owner trust, transfer,
  auto-closing doors, inventory/hopper safeguards, and piston/explosion resistance
- wilderness claims with a management menu, paid claim-block allowance,
  golden-shovel create/resize selection, trust, transfer, and protection
- registered real-estate plots with purchase, fixed-term rent escrow/refunds,
  titleholders, tenants, trusted builders, search, and region protection
- metadata-safe item auctions with bid escrow, buyouts, outbid refunds,
  scheduled settlement, browse GUI, search, and persistent item claims
- configurable IRV/STV elections and strict-majority referendums with tracked
  eligibility, ranked ballots, auditable rounds, and elected office terms
- bicameral bills, Senate amendments, chamber tallies, presidential assent or
  veto, veto overrides, constitutional referendums, and an enacted law codex
- persistent three-level courts with civil, criminal, constitutional, and
  institutional cases, evidence, orders, warrants, verdicts, records, and appeals
- consent-aware PvP incident tracking, forensic clues, `/911` reports, police
  charges, wanted status, court-warrant arrests, fines, and restart-safe custody
- persistent injuries and diseases, symptom-only patient views, doctor diagnosis,
  contagious spread, temperature illness, crafted treatments, hospital call
  monitors, pharmacy counters, Medicare benefits, and Department of Health chat
- persisted global, local, murmur, and government channels, private messaging
  and replies, offline mail, help questions, and qualification-gated advertising
- persistent named homes and civic warps, async safe teleport, coordinate sharing,
  configurable web-map link, and compass/action-bar GPS for registered plots
- reciprocal friend consent, lawyer-officiated marriage, audited divorce, shared
  partner home, unlogged partner chat, and two-sided partner PvP consent
- config-driven road, water, and air vehicles with native Paper controls,
  permanent driver/pilot qualifications, owner locks and transfers, persistent
  fuel, damage, repair, trunk storage, mechanic recipes, and chunk recovery
- business-owned private stock exchanges with issuer applications, named share
  registers, price-time limit orders, cash/share escrow, configurable fees,
  portfolios, trade history, regulatory halts, and issuer-funded dividends
- optional Redis-backed multi-node presence with stable UUID/name identity,
  node leases, `/network` diagnostics, and replay-filtered global and department
  chat bridging that leaves proximity chat local
- craftable wall-mounted security cameras and computers with persistent owner
  registration, groups, public/private allow lists, dashboard controls, native
  spectator feeds, camera switching and rotation, and crash-safe player recovery
- aligned iron-block elevators with jump-up, sneak-down, nearest-floor routing,
  collision-safe arrival, per-world controls, and a bounded reuse cooldown
- asynchronous database access and atomic transfers

The authoritative implementation status is in [docs/PARITY.md](docs/PARITY.md).

## Build

```bash
./gradlew clean test build
```

Copy `build/libs/opencivitas-0.1.0-SNAPSHOT.jar` to a Paper 1.21.11 server's
`plugins` directory. The SQLite JDBC driver and Jedis transport client are
resolved by Paper from the `libraries` declaration in `plugin.yml`.

## Configuration

Configuration is generated at `plugins/OpenCivitas/config.yml`, with domain
files including `vehicles.yml`, `stocks.yml`, `network.yml`, `security.yml`,
`elevators.yml`, and `block-protection.yml`.
Language files are generated at `plugins/OpenCivitas/lang/`. MiniMessage tags are
supported in all catalog strings.

## Network deployment

Networking is disabled by default. To bridge live presence and configured chat
channels, set `enabled: true` in `network.yml`, give every concurrent Paper node
a unique node id, and expose a `redis://` or `rediss://` URI through the
environment variable named by `redis.uri-environment` (default
`OPENCIVITAS_REDIS_URL`). Credentials are never stored in configuration or
written to logs.

Every server sharing a Redis namespace is part of the same trust boundary.
Network chat is deliberately transient and is not queued while a node is
disconnected. SQLite civic records, balances, inventories, mail, and other
persistent state are not synchronized by this transport. `/network status`,
`/network servers`, and `/network who <player|uuid>` expose its live state.

## Design rules

- All currency is stored as integer cents; floating-point money is never used.
- Database operations never run on the server tick thread after startup.
- Persistent state changes are transactional and auditable.
- Cross-server chat carries no history and presence keys expire after missed
  heartbeats; transport failure never disables standalone civic behavior.
- PacketEvents is added only for features that require packet interception or
  client-side illusions. Paper 1.21.11 exposes complete vehicle input directly,
  native spectator targets for camera feeds, and a cancellable player jump event,
  plus native block/inventory events for protection, so it is not justified for
  the current vehicle, camera, elevator, or block-protection paths.

## License

[MIT](LICENSE)

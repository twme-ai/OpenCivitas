# Research log

Research was performed on 18 July 2026. Public documentation is treated as a
behavioral reference, not a source of copyable implementation or content.

## Official behavior sources

- [DemocracyCraft Getting Started](https://wiki.democracycraft.net/Getting_Started):
  starting balance, earning paths, job limits, healthcare, government branches,
  world rules, property, businesses, and chat modes.
- [DemocracyCraft Jobs](https://wiki.democracycraft.net/Jobs): trade,
  profession, government, license, and legal qualification categories.
- [DemocracyCraft Government](https://wiki.democracycraft.net/Government):
  bicameral legislature, executive, courts, office terms, eligibility, vetoes,
  succession, and voting systems.
- [Current public Constitution](https://www.democracycraft.net/threads/constitution.6/):
  legislative initiative, review and amendment sequence, quorum, dynamic and
  absolute vote thresholds, assent deadlines, vetoes and overrides,
  constitutional referendums, election rights, and judicial jurisdiction.
- [DemocracyCraft Health](https://wiki.democracycraft.net/Health): symptoms,
  diseases, contagious conditions, doctors, hospitals, Medicare, pharmacies,
  and treatment items.
- [DemocracyCraft Useful Commands](https://wiki.democracycraft.net/Useful_commands):
  public command surface for economy, jobs, travel, property, claims, families,
  communications, and general player tools.
- [Paper project API](https://fill.papermc.io/v3/projects/paper): confirms the
  stable Paper 1.21.11 release.
- [Paper plugin metadata](https://docs.papermc.io/paper/dev/plugin-yml/): plugin
  descriptor and runtime Maven library loading.
- [Paper MiniMessage documentation](https://docs.papermc.io/adventure/minimessage/):
  component parsing and safe dynamic replacements.

## Open-source implementation references

- [Paper](https://github.com/PaperMC/Paper): server API and runtime behavior.
- [Adventure](https://github.com/PaperMC/adventure): components, MiniMessage,
  audiences, and localization building blocks.
- [Jobs Reborn](https://github.com/Zrips/Jobs): configurable job actions,
  progression, qualification/permission checks, duplicate membership checks,
  slot limits, and join/leave lifecycle patterns (Apache-2.0). The public
  `commands/list/join.java` and `container/JobsPlayer.java` implementations were
  inspected before the OpenCivitas enrollment transaction was designed.
- The current DemocracyCraft command reference documents `/licenses [player]`
  and `/setprefix <job>`. OpenCivitas stores license expiry separately from
  job qualifications and only resolves a displayed prefix while that job is
  still held, preventing a stale selection from implying a current role.
- [VaultAPI](https://github.com/MilkBowl/VaultAPI): common economy service
  contract and compatibility expectations (LGPL-3.0).
- [QuickShop-Hikari](https://github.com/QuickShop-Community/QuickShop-Hikari):
  container shop interaction and transaction patterns.
- [ChestShop](https://github.com/ChestShop-authors/ChestShop-3): sign-driven
  shops and transaction history (LGPL-2.1).
- [Towny](https://github.com/TownyAdvanced/Towny): residents, claims, towns,
  permissions, and taxes.
- [WorldGuard](https://github.com/EngineHub/WorldGuard): region protection and
  spatial authorization.
- [ElectionPlugin](https://github.com/lucasperecin/ElectionPlugin): modern Paper
  election command and persistence reference.
- A [10 June 2026 archive of DemocracyCraft Government revision 9394](https://web.archive.org/web/20260610182933/https://wiki.democracycraft.net/Government)
  was inspected while the live wiki returned HTTP 500. It documents 11 House seats
  elected by STV for two months, two staggered three-seat Senate classes serving
  four months, four-month presidential IRV, and the 24/72/300-hour candidate
  thresholds with 12 recent hours. The
  [archived Vice President revision 9309](https://web.archive.org/web/20260610182952/https://wiki.democracycraft.net/Vice_president)
  establishes four months of citizenship, 150 total hours, 12 recent hours,
  and exclusion of the most recently elected president for running mates.
- The current public Constitution was cross-checked for election rights and
  legislative thresholds. It requires six active hours in the prior 30 days to
  vote, secret election and referendum ballots, at least half of modified seats
  for congressional quorum, dynamic majorities, absolute affirmative floors of
  four House or two Senate votes, and two-thirds supermajorities unless a
  constitutional veto override requires 80 percent.
- A [24 May 2026 archive of the DemocracyCraft Judiciary page](https://web.archive.org/web/20260524135705/https://wiki.democracycraft.net/Judiciary)
  and the current public Constitution were cross-checked for three-level court
  jurisdiction. District Court covers civil disputes up to `$120,000`, minor
  criminal matters up to `$10,000` or 60 minutes of imprisonment, arrests,
  seizures, and public-official misconduct. Federal Court handles larger cases,
  constitutional matters, warrants, and District appeals. Supreme Court handles
  Federal appeals and institutional disputes through a three-justice panel in
  which at least two justices must agree.
- [LawAndOrder](https://github.com/highspeedtrain/LawAndOrder): MIT-licensed
  lawsuit, judge, criminal-record, and custom-law behavior reference. Its public
  model was used to compare case lifecycle expectations; OpenCivitas uses an
  original transactional schema with explicit jurisdiction, immutable docket
  entries, linked appeals, and concurrence-based Supreme Court decisions.
- A [28 May 2026 archive of the DemocracyCraft Police Officer page](https://web.archive.org/web/20260528032707/https://wiki.democracycraft.net/Police_officer)
  documents arresting from in-game reports, resolving open charges, responding
  to department tickets and alarms, and the police hierarchy. The
  [24 May 2026 Useful Commands archive](https://web.archive.org/web/20260524145749/https://wiki.democracycraft.net/Useful_commands)
  documents `/911` after a player is murdered.
- The public record in
  [RaiTheGuy07 v. Department of Homeland Security](https://www.democracycraft.net/threads/raitheguy07-v-department-of-homeland-security-2025-fcr-21.24901/)
  was cross-checked for `/police consent`, suppression of forensic clues for
  consented killings, wanted status, arrests, jail, and record disputes. The
  current Constitution separately protects self-defense-related due process,
  confrontation of evidence, freedom from unreasonable search or seizure, and
  notice of the reason for detention or arrest.
- [BodyCam](https://github.com/DEV-AdriBOT/BodyCam): MIT-licensed Paper
  1.21.11 evidence capture, immutable identifier, bounded asynchronous writer,
  audit index, and evidence export reference. OpenCivitas currently records
  structured combat snapshots and PDC-tagged physical clues rather than video;
  no packet interception is required.
- [Jail](https://github.com/graywolf336/Jail): GPL-2.0 UUID custody, timed
  release, restart persistence, movement/escape protection, reason display, and
  record-keeping reference. OpenCivitas uses original SQLite transactions and a
  main-thread custody snapshot; no GPL source was copied.
- [PolicePlus](https://github.com/mrpaster12/PolicePlus) was inspected only as
  a public behavioral comparison for wanted status, proximity arrest,
  PDC-tagged police items, configurable jail time, and containment. Its source
  is proprietary, so no implementation code was used.
- [single-transferable-vote](https://github.com/buzzlawless/single-transferable-vote):
  MIT-licensed Droop quota, candidate elimination, surplus transfer, and result
  test reference. OpenCivitas implements its own immutable ballots and
  fractional transfer counter, with every round persisted for deterministic
  recount and audit.
- ElectionPlugin's current `ElectionManager` and command implementations were
  inspected for candidate lifecycle, one-vote enforcement, result publication,
  persistence, and multilingual command expectations. OpenCivitas replaces its
  name-keyed flat-file ballots with transactional UUID identities, replaceable
  ranked ballots, scheduled phases, and SQLite result history.
- [StateCraftMods](https://github.com/tswink44/StateCraftMods): current Minecraft
  legislation model with draft/debate/voting stages, amendments, vote
  replacement, quorum, veto, enactment, persistence, and a law codex. The
  repository has no declared license, so it was used only as a behavioral and
  domain-model reference. OpenCivitas uses original SQLite transactions and a
  Constitution-specific bicameral state machine; no source was copied.
- [QuizMaster](https://github.com/Ansandr/QuizMaster): MIT-licensed question,
  option, active-session, pass-score, and configuration model. Its public
  `QuizManager`, `QuizHandler`, and `QuizConfiguration` were inspected before
  implementing immutable options and direct, transactional rewards.
- [Paper asynchronous teleport documentation](https://docs.papermc.io/paper/dev/entity-teleport/):
  chunk-safe university travel using `teleportAsync`.
- [DemocracyCraft Business](https://wiki.democracycraft.net/Business): public
  firm lifecycle, account, employee, role, wage, and chest-shop command behavior.
- [DemocracyCraft Chestshops](https://wiki.democracycraft.net/Chestshops): sign
  format, buy/sell interaction, firm account prefix, and sales history behavior.
- [QuickShop-Hikari economy transactions](https://github.com/QuickShop-Community/QuickShop-Hikari/tree/hikari/quickshop-bukkit/src/main/java/com/ghostchu/quickshop/economy/transaction):
  preflight balance checks, commit/rollback, callbacks, and transaction events.
  OpenCivitas uses a single SQLite transaction for the current internal account
  transfers and keeps the same all-or-nothing invariant.
- [MyCompany](https://github.com/Ez4p1xEL/MyCompany): MIT-licensed company,
  persisted employee, hire request, position, salary, and ownership reference.
  Its current `HireRequestManager`, `CompanyManager`, configuration, and salary
  event were inspected before implementing restart-safe offers and payroll.
- [Towny invitations](https://github.com/TownyAdvanced/Towny/tree/master/Towny/src/main/java/com/palmergames/bukkit/towny/invites):
  explicit sender/receiver ownership and accept/decline lifecycle reference.
- [QuickShop permission groups](https://github.com/QuickShop-Community/QuickShop-Hikari/blob/hikari/quickshop-bukkit/src/main/java/com/ghostchu/quickshop/shop/SimpleShopPermissionManager.java):
  named permission group and least-privilege shop access reference for the firm
  hierarchy and the upcoming custom-role/shop integration.
- [ChestShop sign model](https://github.com/ChestShop-authors/ChestShop-3/blob/master/plugin/src/main/java/com/Acrobot/ChestShop/Signs/ChestShopSign.java),
  [amount/price checks](https://github.com/ChestShop-authors/ChestShop-3/blob/master/plugin/src/main/java/com/Acrobot/ChestShop/Listeners/PreTransaction/AmountAndPriceChecker.java),
  and [stock fitting checks](https://github.com/ChestShop-authors/ChestShop-3/blob/master/plugin/src/main/java/com/Acrobot/ChestShop/Listeners/PreTransaction/StockFittingChecker.java):
  the documented four-line format, explicit transaction direction, payer
  preflight, source stock check, and destination capacity check were inspected
  before the OpenCivitas shop parser and inventory reservation lifecycle were
  implemented. No LGPL source was copied.
- [ChestShop item transfer](https://github.com/ChestShop-authors/ChestShop-3/blob/master/plugin/src/main/java/com/Acrobot/ChestShop/Listeners/PostTransaction/ItemManager.java)
  and [transaction logging](https://github.com/ChestShop-authors/ChestShop-3/blob/master/plugin/src/main/java/com/Acrobot/ChestShop/Listeners/PostTransaction/TransactionLogger.java):
  inventory updates and auditable sale records informed OpenCivitas's own
  clean-room implementation. SQLite settlement is atomic; Bukkit items are
  reserved before payment and restored on rejection, with overflow dropped at
  the relevant inventory instead of being deleted.
- [Paper Sign API](https://jd.papermc.io/paper/1.21.11/org/bukkit/block/Sign.html)
  and [PlayerInteractEvent API](https://jd.papermc.io/paper/1.21.11/org/bukkit/event/player/PlayerInteractEvent.html):
  persistent tile-state data, front-side Adventure components, waxed signs,
  main-hand filtering, and left/right block actions cover shop creation and
  interaction without packet interception.
- [DemocracyCraft Wilderness Claiming](https://wiki.democracycraft.net/Wilderness_Claiming):
  current revision 9515 documents wilderness-only self-service claims, a
  4,096-block limit, 10 free blocks, `$20` additional blocks, golden-shovel
  corner resizing, trust, transfer, explosion policy, and kick-out commands.
- [GriefPrevention](https://github.com/GriefPrevention/GriefPrevention):
  GPL-3.0 claim geometry, overlap checks, explicit claim activation, trust
  levels, transfers, explosion flags, boundary visualization, and remaining
  block accounting were inspected as behavioral and engineering references.
  OpenCivitas uses original records, SQLite transactions, and an immutable
  tick-thread spatial snapshot; no GPL source was copied.
- [AreaShop](https://github.com/NLthijs48/AreaShop): GPL-3.0 buy/rent region,
  friend access, owner, price, duration, and reset lifecycle reference for the
  upcoming real-estate implementation.
- [AdvancedRegionMarket](https://github.com/LibertyLand/AdvancedRegionMarket):
  region lookup, transactional purchase, ownership/member authorization,
  resale, and search command behavior were inspected as an additional public
  real-estate reference.
- [RealEstate](https://github.com/EtienneDx/RealEstate): MIT-licensed claim
  sale, rent, lease, auction, exit-offer, and multi-protection-provider model;
  retained as a reference for the upcoming property and auction settlement
  work.
- The current [DemocracyCraft Useful Commands](https://wiki.democracycraft.net/Useful_commands)
  real-estate surface was rechecked before implementation: `/rl me`, instant
  purchase, trusted-player add/remove, fixed-term rent and early unrent refund,
  price search, plot info, titleholder assignment, and tenant assignment.
  OpenCivitas adds operator-only cuboid registration so original server worlds
  can define their own plot inventory without copying proprietary map data.
- AreaShop's current `RentRegion`, `BuyRegion`, rent/unrent, friend, and owner
  command implementations were inspected for expiry scheduling, owner-only
  access changes, available-region filtering, and fixed-term lifecycle.
  AdvancedRegionMarket's buy/member commands and RealEstate's persisted
  rent/lease update loop were used to cross-check those behaviors. OpenCivitas
  holds prepaid rent in an auditable SQLite escrow value and conserves every
  cent across proportional refund plus landlord payout.
- [Auctify](https://github.com/PteroxOS/Auctify) and
  [AuctionHouse](https://github.com/Nikster1234/AuctionHouse) are current
  MIT-licensed Paper auction implementations. Their per-listing locking,
  escrow withdrawal, outbid refund, seller settlement, restart expiry, search,
  cancellation, and persistent claim flows were inspected before the
  OpenCivitas auction transaction was designed.
- [CrazyAuctions](https://github.com/Crazy-Crew/CrazyAuctions) and
  [GlobalMarketChest](https://github.com/EpiCanard/GlobalMarketChest) provide
  additional MIT-licensed GUI auction and global marketplace references.
  OpenCivitas uses Paper's native `ItemStack` byte serialization, SQLite
  integer-cents escrow, immutable listing history, and claim records; it does
  not copy reference source or GUI assets.
- A [5 September 2025 archive of DemocracyCraft Health](https://web.archive.org/web/20250905224644/https://wiki.democracycraft.net/Health)
  and a [30 May 2026 Doctor archive](https://web.archive.org/web/20260530093330/https://wiki.democracycraft.net/Doctor)
  were cross-checked for private symptoms, doctor-only diagnosis, body
  temperature, hospital call monitors, doctor attendance, treatment items,
  nearby-player transmission, pharmacy self-treatment, `/doh`, `/health`, and
  `/bulkbill`. Public condition pages were individually inspected for broken
  limbs, flesh wounds, cholera, chicken pox, common cold, culicid fever, heat
  exhaustion, hypothermia, Lyme disease, mad cow disease, salmonella, and
  tetanus, including their documented causes, symptoms, recipe ingredients,
  and the published 3- or 5-block transmission distances where available.
- Purple pimples, webbed feet, hyperglycemia, and clogged ears appear in the
  public Health catalog as pharmacy-treated conditions, but their detailed
  pages and recipes were not publicly recoverable. OpenCivitas therefore keeps
  their original clean-room exposure, symptom, item, and co-pay defaults fully
  editable in `health.yml` rather than presenting those defaults as exact
  DemocracyCraft data.
- [Immersive Health System](https://github.com/whoisyonaa/Immersive-Health-System)
  is an MIT-licensed modern Paper reference for configurable diseases, staged
  symptoms, transmission, PDC-tagged medicines, and persistent per-player
  illness state. OpenCivitas uses an original SQLite model with one active row
  per condition, immutable treatment history, exclusive medical-call claims,
  and exactly-once Medicare ledger settlement; no reference source was copied.
- The [24 May 2026 Useful Commands archive](https://web.archive.org/web/20260524145749/https://wiki.democracycraft.net/Useful_commands)
  was rechecked for `/g`, `/l`, `/murmur`, `/vc`, `/msg`, `/tell`, `/r`,
  offline `/mail send`, `/ask`, entrepreneur-qualified `/ad` with a ten-minute
  cooldown, emoji discovery, and government channels including `/doj`, `/sen`,
  and `/jud`. The public page does not disclose local or murmur radii, so
  OpenCivitas uses conservative original defaults of 100 and 8 blocks in
  `chat.yml`; operators can change both without code changes.
- [Carbon](https://github.com/Hexaoxide/Carbon) and
  [VentureChat](https://github.com/Aust1n46/VentureChat) are GPL-3.0 channel
  references. Carbon's current public channel, whisper, and reply models were
  inspected for per-channel commands, read/speak authorization, configurable
  radii, empty-radius feedback, cooldowns, recipient-specific rendering,
  reciprocal reply targets, and online-target validation. OpenCivitas uses an
  original Paper event router and SQLite repository; no GPL source was copied.
  Direct-message content is deliberately not stored, while offline mail is
  persisted with recipient ownership, read state, and soft deletion.
- The same Useful Commands archive was cross-checked for `/map`, `/coords`,
  `/sendcoords`, `/gps start`, `/gps stop`, `/directions`, named `/sethome` and
  `/home`, central/north/south/university/airport warps, and the Oakridge,
  Willow, and Aventura district aliases. OpenCivitas resolves GPS only against
  locally registered real-estate plots and civic warps configured by the
  operator; it does not copy DemocracyCraft coordinates, streets, or map data.
- [EssentialsX](https://github.com/EssentialsX/Essentials) is a GPL-3.0 home,
  warp, and teleport behavioral reference. Its current `Commandhome`,
  `Commandsethome`, and `AsyncTeleport` implementations were inspected for
  named-home limits that still allow updates, case-normalized names, missing
  destination handling, world-border checks, asynchronous chunk loading,
  passable destination validation, and completion-aware teleport feedback.
  OpenCivitas uses original SQLite and Paper API code; no GPL source was copied.
- A [25 July 2025 archive of DemocracyCraft Families](https://web.archive.org/web/20250725231134/https://wiki.democracycraft.net/Families)
  establishes that a lawyer officiates marriage after handling its legal
  requirements, while adoption is processed through the Department of Public
  Affairs forum rather than a public in-game command. OpenCivitas consequently
  requires current consent from both prospective spouses plus a configured
  lawyer job or qualification, records the officiant, and leaves adoption as an
  external government workflow.
- [MarriageMaster](https://github.com/GeorgH93/MarriageMaster) and
  [Marriage](https://github.com/lenis0012/Marriage) are GPL-3.0 relationship
  references. MarriageMaster's public README, API tree, and feature model were
  inspected for proposals, marriage/divorce events, partner homes, private
  partner chat, partner teleport, and configurable partner PvP. OpenCivitas
  uses an original consent-first SQLite model and immutable in-memory combat
  snapshot; no GPL source was copied and partner chat content is not stored.
- A [28 May 2026 archive of the DemocracyCraft Mechanic page](https://web.archive.org/web/20260528083203/https://wiki.democracycraft.net/Mechanic),
  [28 May 2026 Driver's licence archive](https://web.archive.org/web/20260528032710/https://wiki.democracycraft.net/Driver%27s_licence),
  and [10 June 2026 Pilot's license archive](https://web.archive.org/web/20260610182933/https://wiki.democracycraft.net/Pilot%27s_license)
  were cross-checked for mechanic-only part, vehicle, and fuel crafting;
  permanent driver and pilot exam qualifications; owner interaction; road and
  parking rules; refuel, pickup, entry, trunk, and aircraft altitude controls;
  player trade and chest-shop acquisition; and the two published fuel recipes.
  The archived recipe images establish a bucket surrounded by alternating
  emeralds and either coal or dried-kelp blocks. Vehicle models, proprietary
  recipes, city geometry, and dealership assets were not copied.
- [VehiclesWASD](https://github.com/aematsubara/VehiclesWASD) is a current
  GPL-3.0 Paper vehicle reference. Its public type, input, ownership, lock,
  fuel, storage, periodic save, and movement boundaries were inspected.
  OpenCivitas uses original configuration, SQLite transactions, native Paper
  entities and item displays, and `ItemStack` byte serialization; no GPL source,
  models, textures, or configuration was copied.
- [Paper PlayerInputEvent](https://jd.papermc.io/paper/1.21.11/org/bukkit/event/player/PlayerInputEvent.html)
  exposes forward, backward, left, right, jump, sneak, and sprint state on the
  server thread. It replaces the older steer-packet interception used by
  multi-version vehicle projects for the fixed Paper 1.21.11 target.
- The current [DemocracyCraft Stock Exchange page](https://wiki.democracycraft.net/Stock_Exchange)
  revision 9492 was inspected for private exchange ownership, stocks and ETFs,
  shares as registered company ownership, primary issuance used to finance
  company expansion, investor loss risk, exchange-specific buy/sell procedures,
  transfer fees, activity limits, and the absence of any guaranteed repurchase.
  OpenCivitas implements the disclosed common behavior in-game while leaving
  each operator free to set its fee and decide which issuer applications to
  approve.
- The public record in
  [Admin23 v. The Exchange](https://www.democracycraft.net/threads/admin23-v-the-exchange-2022-scr-17.14483/)
  was cross-checked for customer-defined sale prices, exchange cash accounts,
  commissions, direct share transfers, supply-and-demand price formation,
  periodic market-price duties, and disclosure of holdings over 20 percent.
  Current 2026 forum legislation was also checked for Department of Commerce
  trading halts and the requirement that every share be registered to a named
  holder rather than bearer. The public shareholder register exposes all
  holdings and percentages, including beneficial shares escrowed in sell
  orders, so large positions are directly auditable.
- [StockMarketPlugin](https://github.com/Antidino72/StockMarketPlugin) is a
  current MIT-licensed Minecraft reference for quote caching, buy/sell menus,
  portfolios, and bilingual market presentation. Its synthetic external-price
  and separate Vault/YAML writes do not meet OpenCivitas's company-share or
  atomic-conservation requirements, so only its public feature boundaries were
  used.
- [stock-market](https://github.com/maldahleh/stock-market) is a current
  GPL-3.0 Minecraft reference for broker fees, persisted transactions,
  portfolios, price history, purchase/sale preflights, and test coverage.
  OpenCivitas uses an original integer-cent SQLite matching engine tied to its
  own business and account ledgers; no GPL source or UI assets were copied.

## Network references

- Public DemocracyCraft wiki searches for network, hub, realm, proxy, and
  cross-server behavior did not expose a player-facing command or durable
  messaging contract. The only server-related result was internal control-panel
  administration. OpenCivitas therefore treats this domain as optional operator
  infrastructure and does not invent proprietary topology, server names, or
  routing behavior.
- [Redis Pub/Sub](https://redis.io/docs/latest/develop/pubsub/) documents ordered,
  at-most-once live delivery: a message missed during a disconnect is not
  replayed. That matches chat's privacy boundary, so OpenCivitas does not persist
  or queue network chat. Its message UUID/time filter additionally rejects
  duplicate, stale, and implausibly future envelopes.
- Redis [key expiry](https://redis.io/docs/latest/commands/expire/) provides the
  failure detector for node and player presence. Heartbeats refresh bounded TTLs;
  Lua compare-and-delete operations ensure shutdown cannot remove a newer process
  or another node's identity lease.
- [Jedis 7.5.3](https://github.com/redis/jedis/releases/tag/v7.5.3) was selected as
  the current stable, MIT-licensed Java client. OpenCivitas uses its pooled
  command client plus a dedicated subscriber connection and keeps the secret URI
  in an operator-selected environment variable rather than YAML or logs.
- [HuskSync's Redis manager](https://github.com/WiIIiam278/HuskSync/blob/62d8b7a8173c48b94b9e1b33635498ea1c143614/common/src/main/java/net/william278/husksync/redis/RedisManager.java)
  was inspected as a current Apache-2.0 Minecraft reference for a separate
  subscriber connection, reconnect handling, namespaced channels, and target
  identity in cross-server messages. OpenCivitas uses an original bounded binary
  chat envelope and presence lease model; no player snapshot synchronization or
  source was copied.
- Paper's [Velocity plugin messaging documentation](https://docs.papermc.io/velocity/dev/plugin-messaging/)
  shows that proxy plugin messages depend on proxy configuration and player
  connections. Redis is used for server-to-server presence and chat so an empty
  backend can still participate. Proxy transfers remain operator infrastructure
  rather than an assumed network topology.

## Build automation references

- [actions/checkout](https://github.com/actions/checkout),
  [actions/setup-java](https://github.com/actions/setup-java),
  [gradle/actions](https://github.com/gradle/actions), and
  [actions/upload-artifact](https://github.com/actions/upload-artifact): official
  GitHub Actions used by the continuous integration workflow. Release versions
  were checked through the GitHub API before the workflow was added.

## PacketEvents decision

The implementation does not intercept, rewrite, or synthesize protocol packets.
Paper APIs are sufficient for commands, components, locale selection, joins,
SQLite persistence, complete vehicle input, persistent interaction hitboxes,
and item-display rendering. PacketEvents would duplicate the native input path
and add lifecycle and versioning risk with no benefit. Re-evaluate it only for
security-camera viewpoints or another feature that proves packet-only.

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

## Build automation references

- [actions/checkout](https://github.com/actions/checkout),
  [actions/setup-java](https://github.com/actions/setup-java),
  [gradle/actions](https://github.com/gradle/actions), and
  [actions/upload-artifact](https://github.com/actions/upload-artifact): official
  GitHub Actions used by the continuous integration workflow. Release versions
  were checked through the GitHub API before the workflow was added.

## PacketEvents decision

The foundation does not intercept, rewrite, or synthesize protocol packets.
Paper APIs are sufficient for commands, components, locale selection, joins, and
SQLite persistence, so PacketEvents would add lifecycle and versioning risk with
no current benefit. Re-evaluate it for security cameras, vehicle rendering,
client-side election displays, or other packet-only behavior.

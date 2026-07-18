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

# Feature parity matrix

Status values are `implemented`, `in progress`, `planned`, and `external`.
`External` means an operator-owned asset or service is required and cannot be
legally or meaningfully copied from another server.

| Domain | Publicly documented behavior | Status |
| --- | --- | --- |
| Platform | Paper 1.21.11, Java 21 | implemented |
| Messages | MiniMessage and player-selectable locales | implemented |
| Identity | persistent profiles, last known name, join timestamps | implemented |
| Economy | `$1,200` start, balances, payments, transaction history | implemented |
| Jobs | two trades, one profession, unlimited government jobs | in progress: persistent membership, qualification gates, limits, appointments, licenses, verified prefix selection; action earnings remain |
| University | exams and qualifications for jobs/licenses | in progress: localized exam engine, attempt ledger, qualification awards, warp |
| Shops | chest buy/sell, stack modifiers, item search, sales history | in progress: persistent personal/firm shops, sign creation, click/shift-click settlement, search, history, stock and capacity checks |
| Businesses | licensed firms, staff, accounts, deposits, withdrawals, sales | in progress: accounts, offers, five ranks, wages/payroll, ownership, ledgers, firm shops and sales history; custom roles remain |
| Auctions | item listings, bids, settlement | implemented: metadata-safe listings, browse/search, escrow bids, buyouts, refunds, cancellation rules, expiry settlement, persistent claims |
| Property | buy, rent, refund, titleholder, tenant, trusted builders, search | in progress: registered 3D plots, purchase, fixed-term escrow/rent expiry, proportional refund, titleholder/tenant/trust, price search, protection |
| Claims | wilderness claims, transfer, explosion policy, kick-out | in progress: 4,096-block allowance, paid expansion, menu/wand create and resize, trust, transfer, protection, explosion toggle, kick-out |
| Government | executive, bicameral legislature, judiciary | in progress: configurable elected offices, persistent office terms, legislature, and judiciary; executive appointments remain |
| Elections | STV, instant runoff, terms, eligibility, referendums | implemented: candidate and six-hour voter eligibility, sealed ranked ballots, nominations/running mates, ballot replacement, IRV, Droop-quota STV, strict-majority referendums, auditable rounds/results, automatic terms |
| Legislature | bills, amendments, chamber votes, veto and override | implemented: House introduction, Senate review/amendments, House reconsideration, immutable stage votes/tallies, quorum/floors, presidential assent/veto, assumed assent, bicameral override, constitutional referendums, enacted law codex |
| Courts | three levels, cases, filings, orders, appeals | implemented: public docket, jurisdiction-routed civil/criminal/constitutional/institutional filings, counsel, judicial assignment, hearings, text/item evidence, motions, expiring orders and warrants, jurisdiction-limited sanctions, criminal records, linked appeals, and two-vote Supreme Court decisions |
| Law enforcement | reports, consent, wanted status, evidence, arrest | planned |
| Health | symptoms, injuries, diseases, contagion, doctors, treatments | planned |
| Medicare | covered hospital treatment and configurable pharmacy co-pay | planned |
| Chat | global, local, murmur, help, department and private channels | planned |
| Navigation | homes, warps, coordinates, compass, GPS/transit routing | planned |
| Families | friends, marriage, partner chat/home/PvP controls | planned |
| Vehicles | cars, aircraft, licenses, ownership, fuel, traffic rules | planned |
| Stocks | firms, exchanges, orders, holdings, dividends | planned |
| Networks | multi-server identity and cross-server channels | planned |
| Security cameras | camera registration, access, remote viewing | planned |
| Elevators | sign or block-based vertical transport | planned |
| Custom world/map | original city, wilderness, towns, transit network | external |
| Branding/assets | original names, textures, models, audio, website | external |

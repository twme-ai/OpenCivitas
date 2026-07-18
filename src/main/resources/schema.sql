CREATE TABLE IF NOT EXISTS players (
    uuid TEXT PRIMARY KEY,
    last_name TEXT NOT NULL COLLATE NOCASE,
    client_locale TEXT NOT NULL,
    preferred_locale TEXT,
    joined_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_players_last_name
    ON players(last_name COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS player_activity_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    started_at INTEGER NOT NULL,
    last_activity_at INTEGER NOT NULL CHECK (last_activity_at >= started_at),
    ended_at INTEGER CHECK (ended_at IS NULL OR ended_at >= started_at)
);

CREATE INDEX IF NOT EXISTS idx_player_activity_player_started
    ON player_activity_sessions(player_uuid, started_at, last_activity_at);

CREATE TABLE IF NOT EXISTS accounts (
    player_uuid TEXT PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
    balance_cents INTEGER NOT NULL CHECK (balance_cents >= 0)
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    counterparty_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    amount_cents INTEGER NOT NULL CHECK (amount_cents != 0),
    entry_type TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ledger_player_created
    ON ledger_entries(player_uuid, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS qualifications (
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    qualification_id TEXT NOT NULL,
    granted_by TEXT,
    granted_at INTEGER NOT NULL,
    PRIMARY KEY (player_uuid, qualification_id)
);

CREATE TABLE IF NOT EXISTS citizen_jobs (
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    job_id TEXT NOT NULL,
    category TEXT NOT NULL,
    joined_at INTEGER NOT NULL,
    appointed_by TEXT,
    PRIMARY KEY (player_uuid, job_id)
);

CREATE INDEX IF NOT EXISTS idx_citizen_jobs_player_category
    ON citizen_jobs(player_uuid, category);

CREATE TABLE IF NOT EXISTS licenses (
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    license_id TEXT NOT NULL,
    granted_by TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    granted_at INTEGER NOT NULL,
    expires_at INTEGER,
    PRIMARY KEY (player_uuid, license_id)
);

CREATE INDEX IF NOT EXISTS idx_licenses_player_expires
    ON licenses(player_uuid, expires_at, license_id);

CREATE TABLE IF NOT EXISTS player_prefixes (
    player_uuid TEXT PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
    job_id TEXT NOT NULL,
    selected_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS elections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    slug TEXT NOT NULL UNIQUE COLLATE NOCASE,
    title TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('OFFICE', 'REFERENDUM')),
    office_id TEXT,
    method TEXT NOT NULL CHECK (method IN ('IRV', 'STV', 'REFERENDUM')),
    seats INTEGER NOT NULL CHECK (seats > 0),
    term_days INTEGER NOT NULL CHECK (term_days >= 0),
    running_mate_required INTEGER NOT NULL DEFAULT 0 CHECK (running_mate_required IN (0, 1)),
    running_mate_office TEXT,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    created_by TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    created_at INTEGER NOT NULL,
    nominations_close_at INTEGER NOT NULL,
    voting_opens_at INTEGER NOT NULL,
    voting_closes_at INTEGER NOT NULL,
    closed_at INTEGER,
    CHECK (nominations_close_at <= voting_opens_at),
    CHECK (voting_opens_at < voting_closes_at)
);

CREATE INDEX IF NOT EXISTS idx_elections_status_close
    ON elections(status, voting_closes_at, id);

CREATE TABLE IF NOT EXISTS election_choices (
    election_id INTEGER NOT NULL REFERENCES elections(id) ON DELETE CASCADE,
    choice_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    candidate_uuid TEXT REFERENCES players(uuid) ON DELETE RESTRICT,
    running_mate_uuid TEXT REFERENCES players(uuid) ON DELETE RESTRICT,
    running_mate_name TEXT,
    nominated_at INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    PRIMARY KEY (election_id, choice_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_election_candidate_once
    ON election_choices(election_id, candidate_uuid) WHERE candidate_uuid IS NOT NULL;

CREATE TABLE IF NOT EXISTS election_voters (
    election_id INTEGER NOT NULL REFERENCES elections(id) ON DELETE CASCADE,
    voter_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    ballot_id TEXT NOT NULL UNIQUE,
    cast_at INTEGER NOT NULL,
    PRIMARY KEY (election_id, voter_uuid)
);

CREATE TABLE IF NOT EXISTS election_ballot_preferences (
    ballot_id TEXT NOT NULL REFERENCES election_voters(ballot_id) ON DELETE CASCADE,
    rank INTEGER NOT NULL CHECK (rank > 0),
    choice_id TEXT NOT NULL,
    PRIMARY KEY (ballot_id, rank),
    UNIQUE (ballot_id, choice_id)
);

CREATE TABLE IF NOT EXISTS election_round_results (
    election_id INTEGER NOT NULL REFERENCES elections(id) ON DELETE CASCADE,
    round_number INTEGER NOT NULL CHECK (round_number > 0),
    choice_id TEXT NOT NULL,
    tally_micros INTEGER NOT NULL CHECK (tally_micros >= 0),
    disposition TEXT,
    PRIMARY KEY (election_id, round_number, choice_id)
);

CREATE TABLE IF NOT EXISTS election_results (
    election_id INTEGER NOT NULL REFERENCES elections(id) ON DELETE CASCADE,
    choice_id TEXT NOT NULL,
    placement INTEGER NOT NULL CHECK (placement > 0),
    elected INTEGER NOT NULL CHECK (elected IN (0, 1)),
    final_tally_micros INTEGER NOT NULL CHECK (final_tally_micros >= 0),
    PRIMARY KEY (election_id, choice_id)
);

CREATE TABLE IF NOT EXISTS office_terms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    office_id TEXT NOT NULL,
    seat_number INTEGER NOT NULL CHECK (seat_number > 0),
    holder_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    election_id INTEGER REFERENCES elections(id) ON DELETE SET NULL,
    started_at INTEGER NOT NULL,
    ends_at INTEGER NOT NULL CHECK (ends_at > started_at),
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED', 'SUPERSEDED', 'VACATED'))
);

CREATE INDEX IF NOT EXISTS idx_office_terms_active
    ON office_terms(office_id, status, ends_at, seat_number);

CREATE TABLE IF NOT EXISTS legislative_bills (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bill_number TEXT UNIQUE,
    bill_type TEXT NOT NULL CHECK (bill_type IN ('REGULAR', 'CONSTITUTIONAL', 'APPROPRIATION', 'RESOLUTION')),
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    author_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    status TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    presidential_deadline INTEGER,
    veto_reason TEXT,
    referendum_election_id INTEGER REFERENCES elections(id) ON DELETE SET NULL,
    enacted_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_legislative_bills_status_updated
    ON legislative_bills(status, updated_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS legislative_amendments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bill_id INTEGER NOT NULL REFERENCES legislative_bills(id) ON DELETE CASCADE,
    author_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    amendment_text TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED', 'ADOPTED', 'REJECTED')),
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS legislative_votes (
    bill_id INTEGER NOT NULL REFERENCES legislative_bills(id) ON DELETE CASCADE,
    stage TEXT NOT NULL,
    voter_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    vote TEXT NOT NULL CHECK (vote IN ('YES', 'NO', 'ABSTAIN')),
    cast_at INTEGER NOT NULL,
    PRIMARY KEY (bill_id, stage, voter_uuid)
);

CREATE TABLE IF NOT EXISTS legislative_vote_results (
    bill_id INTEGER NOT NULL REFERENCES legislative_bills(id) ON DELETE CASCADE,
    stage TEXT NOT NULL,
    yes_votes INTEGER NOT NULL CHECK (yes_votes >= 0),
    no_votes INTEGER NOT NULL CHECK (no_votes >= 0),
    abstain_votes INTEGER NOT NULL CHECK (abstain_votes >= 0),
    quorum_required INTEGER NOT NULL CHECK (quorum_required >= 0),
    threshold TEXT NOT NULL,
    passed INTEGER NOT NULL CHECK (passed IN (0, 1)),
    tallied_at INTEGER NOT NULL,
    PRIMARY KEY (bill_id, stage)
);

CREATE TABLE IF NOT EXISTS legislative_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bill_id INTEGER NOT NULL REFERENCES legislative_bills(id) ON DELETE CASCADE,
    actor_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    event_type TEXT NOT NULL,
    detail TEXT,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_legislative_events_bill_created
    ON legislative_events(bill_id, created_at, id);

CREATE TABLE IF NOT EXISTS enacted_laws (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bill_id INTEGER NOT NULL UNIQUE REFERENCES legislative_bills(id) ON DELETE RESTRICT,
    law_number TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    law_type TEXT NOT NULL,
    enacted_at INTEGER NOT NULL,
    repealed_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_enacted_laws_active
    ON enacted_laws(repealed_at, enacted_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS court_cases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    case_number TEXT UNIQUE,
    court_level TEXT NOT NULL CHECK (court_level IN ('DISTRICT', 'FEDERAL', 'SUPREME')),
    case_type TEXT NOT NULL CHECK (case_type IN ('CIVIL', 'CRIMINAL', 'CONSTITUTIONAL', 'INSTITUTIONAL', 'APPEAL')),
    status TEXT NOT NULL CHECK (status IN ('FILED', 'ASSIGNED', 'SCHEDULED', 'HEARING', 'DECIDED', 'APPEALED', 'DISMISSED', 'CLOSED')),
    parent_case_id INTEGER REFERENCES court_cases(id) ON DELETE SET NULL,
    filer_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    plaintiff_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    defendant_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    title TEXT NOT NULL,
    claim_text TEXT NOT NULL,
    claim_amount_cents INTEGER NOT NULL DEFAULT 0 CHECK (claim_amount_cents >= 0),
    filed_at INTEGER NOT NULL,
    scheduled_at INTEGER,
    decided_at INTEGER,
    outcome TEXT,
    decision_text TEXT,
    judgment_cents INTEGER NOT NULL DEFAULT 0 CHECK (judgment_cents >= 0),
    fine_cents INTEGER NOT NULL DEFAULT 0 CHECK (fine_cents >= 0),
    jail_minutes INTEGER NOT NULL DEFAULT 0 CHECK (jail_minutes >= 0)
);

CREATE INDEX IF NOT EXISTS idx_court_cases_status_filed
    ON court_cases(status, filed_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS court_participants (
    case_id INTEGER NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE,
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    role TEXT NOT NULL CHECK (role IN ('PLAINTIFF_COUNSEL', 'DEFENSE_COUNSEL')),
    appointed_by TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    appointed_at INTEGER NOT NULL,
    PRIMARY KEY (case_id, role)
);

CREATE TABLE IF NOT EXISTS court_case_judges (
    case_id INTEGER NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE,
    judge_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    judicial_role TEXT NOT NULL,
    joined_at INTEGER NOT NULL,
    PRIMARY KEY (case_id, judge_uuid)
);

CREATE TABLE IF NOT EXISTS court_evidence (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    case_id INTEGER NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE,
    submitted_by TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    description TEXT NOT NULL,
    item_data BLOB,
    submitted_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS court_docket (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    case_id INTEGER NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE,
    actor_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    entry_type TEXT NOT NULL,
    entry_text TEXT,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_court_docket_case_created
    ON court_docket(case_id, created_at, id);

CREATE TABLE IF NOT EXISTS court_verdict_votes (
    case_id INTEGER NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE,
    judge_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    outcome TEXT NOT NULL,
    judgment_cents INTEGER NOT NULL DEFAULT 0 CHECK (judgment_cents >= 0),
    fine_cents INTEGER NOT NULL DEFAULT 0 CHECK (fine_cents >= 0),
    jail_minutes INTEGER NOT NULL DEFAULT 0 CHECK (jail_minutes >= 0),
    reasoning TEXT NOT NULL,
    voted_at INTEGER NOT NULL,
    PRIMARY KEY (case_id, judge_uuid)
);

CREATE TABLE IF NOT EXISTS court_orders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    case_id INTEGER NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE,
    judge_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    target_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    order_type TEXT NOT NULL,
    order_text TEXT NOT NULL,
    issued_at INTEGER NOT NULL,
    expires_at INTEGER,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLIED', 'REVOKED', 'EXPIRED'))
);

CREATE TABLE IF NOT EXISTS court_warrants (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    case_id INTEGER NOT NULL REFERENCES court_cases(id) ON DELETE CASCADE,
    judge_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    target_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    warrant_type TEXT NOT NULL CHECK (warrant_type IN ('ARREST', 'SEARCH')),
    reason TEXT NOT NULL,
    issued_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > issued_at),
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXECUTED', 'REVOKED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_court_warrants_target_status
    ON court_warrants(target_uuid, status, expires_at);

CREATE TABLE IF NOT EXISTS criminal_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    case_id INTEGER NOT NULL UNIQUE REFERENCES court_cases(id) ON DELETE RESTRICT,
    defendant_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    charge TEXT NOT NULL,
    fine_cents INTEGER NOT NULL CHECK (fine_cents >= 0),
    jail_minutes INTEGER NOT NULL CHECK (jail_minutes >= 0),
    convicted_at INTEGER NOT NULL,
    pardoned_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_criminal_records_defendant
    ON criminal_records(defendant_uuid, convicted_at DESC);

CREATE TABLE IF NOT EXISTS pvp_preferences (
    player_uuid TEXT PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
    consent_enabled INTEGER NOT NULL DEFAULT 0 CHECK (consent_enabled IN (0, 1)),
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS combat_incidents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    attacker_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    victim_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    world_name TEXT NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    z REAL NOT NULL,
    damage_millihearts INTEGER NOT NULL CHECK (damage_millihearts >= 0),
    damage_cause TEXT NOT NULL,
    weapon_data BLOB,
    legal_basis TEXT NOT NULL CHECK (legal_basis IN ('UNLAWFUL', 'CONSENT', 'SELF_DEFENSE')),
    attacked_at INTEGER NOT NULL,
    fatal INTEGER NOT NULL DEFAULT 0 CHECK (fatal IN (0, 1)),
    death_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_combat_incidents_pair_time
    ON combat_incidents(attacker_uuid, victim_uuid, attacked_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_combat_incidents_victim_death
    ON combat_incidents(victim_uuid, fatal, death_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS forensic_clues (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    incident_id INTEGER NOT NULL UNIQUE REFERENCES combat_incidents(id) ON DELETE RESTRICT,
    status TEXT NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'COLLECTED', 'VOIDED')),
    collector_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    created_at INTEGER NOT NULL,
    collected_at INTEGER,
    CHECK ((status = 'COLLECTED' AND collector_uuid IS NOT NULL AND collected_at IS NOT NULL)
        OR status != 'COLLECTED')
);

CREATE TABLE IF NOT EXISTS police_reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    incident_id INTEGER NOT NULL UNIQUE REFERENCES combat_incidents(id) ON DELETE RESTRICT,
    reporter_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    suspect_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLAIMED', 'CHARGED', 'DISMISSED')),
    assigned_officer_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    filed_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    resolution TEXT
);

CREATE INDEX IF NOT EXISTS idx_police_reports_status_filed
    ON police_reports(status, filed_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS police_report_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    report_id INTEGER NOT NULL REFERENCES police_reports(id) ON DELETE CASCADE,
    actor_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    event_type TEXT NOT NULL,
    event_text TEXT,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_police_report_events_report_created
    ON police_report_events(report_id, created_at, id);

CREATE TABLE IF NOT EXISTS police_charges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    report_id INTEGER UNIQUE REFERENCES police_reports(id) ON DELETE SET NULL,
    suspect_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    officer_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    offense_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    fine_cents INTEGER NOT NULL CHECK (fine_cents >= 0),
    jail_minutes INTEGER NOT NULL CHECK (jail_minutes >= 0),
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'SERVED', 'VOIDED')),
    charged_at INTEGER NOT NULL,
    resolved_at INTEGER,
    resolution TEXT
);

CREATE INDEX IF NOT EXISTS idx_police_charges_suspect_status
    ON police_charges(suspect_uuid, status, charged_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS police_arrests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    suspect_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    officer_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    reason TEXT NOT NULL,
    fine_assessed_cents INTEGER NOT NULL CHECK (fine_assessed_cents >= 0),
    fine_collected_cents INTEGER NOT NULL CHECK (fine_collected_cents >= 0),
    jail_minutes INTEGER NOT NULL CHECK (jail_minutes >= 0),
    arrested_at INTEGER NOT NULL,
    release_at INTEGER NOT NULL,
    released_at INTEGER,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED', 'OVERTURNED')),
    CHECK (fine_collected_cents <= fine_assessed_cents),
    CHECK (release_at >= arrested_at)
);

CREATE INDEX IF NOT EXISTS idx_police_arrests_status_release
    ON police_arrests(status, release_at, id);

CREATE INDEX IF NOT EXISTS idx_police_arrests_suspect
    ON police_arrests(suspect_uuid, arrested_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS police_arrest_charges (
    arrest_id INTEGER NOT NULL REFERENCES police_arrests(id) ON DELETE CASCADE,
    charge_id INTEGER NOT NULL UNIQUE REFERENCES police_charges(id) ON DELETE RESTRICT,
    PRIMARY KEY (arrest_id, charge_id)
);

CREATE TABLE IF NOT EXISTS police_arrest_warrants (
    arrest_id INTEGER NOT NULL REFERENCES police_arrests(id) ON DELETE CASCADE,
    warrant_id INTEGER NOT NULL UNIQUE REFERENCES court_warrants(id) ON DELETE RESTRICT,
    PRIMARY KEY (arrest_id, warrant_id)
);

CREATE TABLE IF NOT EXISTS exam_attempts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    exam_id TEXT NOT NULL,
    qualification_id TEXT NOT NULL,
    score INTEGER NOT NULL CHECK (score >= 0 AND score <= total_questions),
    total_questions INTEGER NOT NULL CHECK (total_questions > 0),
    passed INTEGER NOT NULL CHECK (passed IN (0, 1)),
    completed_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_exam_attempts_player_completed
    ON exam_attempts(player_uuid, completed_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS businesses (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    slug TEXT NOT NULL UNIQUE COLLATE NOCASE,
    display_name TEXT NOT NULL,
    proprietor_uuid TEXT NOT NULL REFERENCES players(uuid),
    balance_cents INTEGER NOT NULL DEFAULT 0 CHECK (balance_cents >= 0),
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at INTEGER NOT NULL,
    disbanded_at INTEGER
);

CREATE TABLE IF NOT EXISTS business_members (
    business_id INTEGER NOT NULL REFERENCES businesses(id),
    player_uuid TEXT NOT NULL REFERENCES players(uuid),
    role TEXT NOT NULL,
    wage_cents INTEGER NOT NULL DEFAULT 0 CHECK (wage_cents >= 0),
    joined_at INTEGER NOT NULL,
    PRIMARY KEY (business_id, player_uuid)
);

CREATE INDEX IF NOT EXISTS idx_business_members_player
    ON business_members(player_uuid, business_id);

CREATE TABLE IF NOT EXISTS business_offers (
    business_id INTEGER NOT NULL REFERENCES businesses(id),
    player_uuid TEXT NOT NULL REFERENCES players(uuid),
    offered_by TEXT NOT NULL REFERENCES players(uuid),
    offered_role TEXT NOT NULL,
    offered_wage_cents INTEGER NOT NULL DEFAULT 0 CHECK (offered_wage_cents >= 0),
    offered_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    PRIMARY KEY (business_id, player_uuid)
);

CREATE INDEX IF NOT EXISTS idx_business_offers_player_expires
    ON business_offers(player_uuid, expires_at);

CREATE TABLE IF NOT EXISTS business_ledger_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    business_id INTEGER NOT NULL REFERENCES businesses(id),
    actor_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    counterparty_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    amount_cents INTEGER NOT NULL CHECK (amount_cents != 0),
    entry_type TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_business_ledger_created
    ON business_ledger_entries(business_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS chest_shops (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    world_name TEXT NOT NULL,
    sign_x INTEGER NOT NULL,
    sign_y INTEGER NOT NULL,
    sign_z INTEGER NOT NULL,
    container_x INTEGER NOT NULL,
    container_y INTEGER NOT NULL,
    container_z INTEGER NOT NULL,
    owner_type TEXT NOT NULL CHECK (owner_type IN ('PLAYER', 'BUSINESS')),
    owner_uuid TEXT REFERENCES players(uuid),
    business_id INTEGER REFERENCES businesses(id),
    item_key TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    buy_price_cents INTEGER CHECK (buy_price_cents > 0),
    sell_price_cents INTEGER CHECK (sell_price_cents > 0),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at INTEGER NOT NULL,
    deactivated_at INTEGER,
    CHECK (buy_price_cents IS NOT NULL OR sell_price_cents IS NOT NULL),
    CHECK ((owner_type = 'PLAYER' AND owner_uuid IS NOT NULL AND business_id IS NULL)
        OR (owner_type = 'BUSINESS' AND owner_uuid IS NULL AND business_id IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_chest_shops_active_sign
    ON chest_shops(world_name, sign_x, sign_y, sign_z)
    WHERE active = 1;

CREATE INDEX IF NOT EXISTS idx_chest_shops_item
    ON chest_shops(item_key, active, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_chest_shops_container
    ON chest_shops(world_name, container_x, container_y, container_z, active);

CREATE TABLE IF NOT EXISTS shop_sales (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    shop_id INTEGER NOT NULL REFERENCES chest_shops(id),
    customer_uuid TEXT NOT NULL REFERENCES players(uuid),
    direction TEXT NOT NULL CHECK (direction IN ('BUY', 'SELL')),
    item_key TEXT NOT NULL,
    item_amount INTEGER NOT NULL CHECK (item_amount > 0),
    total_cents INTEGER NOT NULL CHECK (total_cents > 0),
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_shop_sales_shop_created
    ON shop_sales(shop_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_shop_sales_customer_created
    ON shop_sales(customer_uuid, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS claim_accounts (
    player_uuid TEXT PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
    purchased_blocks INTEGER NOT NULL DEFAULT 0 CHECK (purchased_blocks >= 0),
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS wilderness_claims (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_uuid TEXT NOT NULL REFERENCES players(uuid),
    world_name TEXT NOT NULL,
    min_x INTEGER NOT NULL,
    max_x INTEGER NOT NULL,
    min_z INTEGER NOT NULL,
    max_z INTEGER NOT NULL,
    explosions INTEGER NOT NULL DEFAULT 0 CHECK (explosions IN (0, 1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    CHECK (min_x <= max_x AND min_z <= max_z)
);

CREATE INDEX IF NOT EXISTS idx_wilderness_claims_world_bounds
    ON wilderness_claims(world_name, min_x, max_x, min_z, max_z);

CREATE INDEX IF NOT EXISTS idx_wilderness_claims_owner
    ON wilderness_claims(owner_uuid, created_at, id);

CREATE TABLE IF NOT EXISTS claim_trust (
    claim_id INTEGER NOT NULL REFERENCES wilderness_claims(id) ON DELETE CASCADE,
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    added_at INTEGER NOT NULL,
    PRIMARY KEY (claim_id, player_uuid)
);

CREATE INDEX IF NOT EXISTS idx_claim_trust_player
    ON claim_trust(player_uuid, claim_id);

CREATE TABLE IF NOT EXISTS claim_wands (
    player_uuid TEXT PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
    issued_day TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS properties (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    plot_id TEXT NOT NULL UNIQUE COLLATE NOCASE,
    world_name TEXT NOT NULL,
    min_x INTEGER NOT NULL,
    max_x INTEGER NOT NULL,
    min_y INTEGER NOT NULL,
    max_y INTEGER NOT NULL,
    min_z INTEGER NOT NULL,
    max_z INTEGER NOT NULL,
    sale_price_cents INTEGER CHECK (sale_price_cents > 0),
    rent_price_cents INTEGER CHECK (rent_price_cents > 0),
    rent_duration_millis INTEGER NOT NULL CHECK (rent_duration_millis > 0),
    titleholder_uuid TEXT REFERENCES players(uuid),
    tenant_uuid TEXT REFERENCES players(uuid),
    rental_started_at INTEGER,
    rental_ends_at INTEGER,
    rent_paid_cents INTEGER NOT NULL DEFAULT 0 CHECK (rent_paid_cents >= 0),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    CHECK (min_x <= max_x AND min_y <= max_y AND min_z <= max_z),
    CHECK (sale_price_cents IS NOT NULL OR rent_price_cents IS NOT NULL),
    CHECK ((rental_started_at IS NULL AND rental_ends_at IS NULL AND rent_paid_cents = 0)
        OR (rental_started_at IS NOT NULL AND rental_ends_at IS NOT NULL AND rent_paid_cents > 0))
);

CREATE INDEX IF NOT EXISTS idx_properties_world_bounds
    ON properties(world_name, min_x, max_x, min_z, max_z);

CREATE INDEX IF NOT EXISTS idx_properties_titleholder
    ON properties(titleholder_uuid, plot_id);

CREATE INDEX IF NOT EXISTS idx_properties_tenant
    ON properties(tenant_uuid, plot_id);

CREATE INDEX IF NOT EXISTS idx_properties_rental_expiry
    ON properties(rental_ends_at)
    WHERE rental_ends_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS property_trust (
    property_id INTEGER NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    added_at INTEGER NOT NULL,
    PRIMARY KEY (property_id, player_uuid)
);

CREATE TABLE IF NOT EXISTS property_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    property_id INTEGER NOT NULL REFERENCES properties(id),
    actor_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    counterparty_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    transaction_type TEXT NOT NULL,
    amount_cents INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_property_transactions_created
    ON property_transactions(property_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS auction_listings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    seller_uuid TEXT NOT NULL REFERENCES players(uuid),
    item_data BLOB NOT NULL,
    item_key TEXT NOT NULL,
    item_name TEXT NOT NULL,
    item_quantity INTEGER NOT NULL CHECK (item_quantity > 0),
    starting_bid_cents INTEGER NOT NULL CHECK (starting_bid_cents > 0),
    buyout_cents INTEGER CHECK (buyout_cents >= starting_bid_cents),
    current_bid_cents INTEGER NOT NULL DEFAULT 0 CHECK (current_bid_cents >= 0),
    current_bidder_uuid TEXT REFERENCES players(uuid),
    state TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'SOLD', 'EXPIRED', 'CANCELLED')),
    created_at INTEGER NOT NULL,
    ends_at INTEGER NOT NULL,
    settled_at INTEGER,
    CHECK ((current_bid_cents = 0 AND current_bidder_uuid IS NULL)
        OR (current_bid_cents > 0 AND current_bidder_uuid IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_auction_listings_active_ends
    ON auction_listings(state, ends_at, id);

CREATE INDEX IF NOT EXISTS idx_auction_listings_seller
    ON auction_listings(seller_uuid, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS auction_bids (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    listing_id INTEGER NOT NULL REFERENCES auction_listings(id),
    bidder_uuid TEXT NOT NULL REFERENCES players(uuid),
    amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_auction_bids_listing_created
    ON auction_bids(listing_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS auction_claims (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    listing_id INTEGER NOT NULL REFERENCES auction_listings(id),
    player_uuid TEXT NOT NULL REFERENCES players(uuid),
    item_data BLOB NOT NULL,
    item_key TEXT NOT NULL,
    item_name TEXT NOT NULL,
    item_quantity INTEGER NOT NULL CHECK (item_quantity > 0),
    created_at INTEGER NOT NULL,
    claimed_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_auction_claims_player
    ON auction_claims(player_uuid, claimed_at, created_at, id);

CREATE TABLE IF NOT EXISTS health_profiles (
    player_uuid TEXT PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
    temperature_millicelsius INTEGER NOT NULL DEFAULT 37000
        CHECK (temperature_millicelsius BETWEEN 20000 AND 55000),
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS health_conditions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    condition_id TEXT NOT NULL,
    source TEXT NOT NULL,
    acquired_at INTEGER NOT NULL,
    resolved_at INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_health_conditions_active
    ON health_conditions(player_uuid, condition_id)
    WHERE resolved_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_health_conditions_player_history
    ON health_conditions(player_uuid, acquired_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS medical_calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    patient_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    world_name TEXT NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    z REAL NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'CLAIMED', 'ATTENDED', 'CANCELLED')),
    claimed_by TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    created_at INTEGER NOT NULL,
    claimed_at INTEGER,
    attended_at INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_medical_calls_patient_open
    ON medical_calls(patient_uuid)
    WHERE status IN ('OPEN', 'CLAIMED');

CREATE INDEX IF NOT EXISTS idx_medical_calls_status_created
    ON medical_calls(status, created_at, id);

CREATE TABLE IF NOT EXISTS medical_treatments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    patient_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    practitioner_uuid TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    treatment_id TEXT NOT NULL,
    condition_id TEXT NOT NULL,
    administered_at INTEGER NOT NULL,
    medicare_eligible INTEGER NOT NULL DEFAULT 0 CHECK (medicare_eligible IN (0, 1)),
    medicare_benefit_cents INTEGER NOT NULL DEFAULT 0 CHECK (medicare_benefit_cents >= 0),
    billed_at INTEGER,
    CHECK ((billed_at IS NULL) OR medicare_eligible = 1)
);

CREATE INDEX IF NOT EXISTS idx_medical_treatments_billing
    ON medical_treatments(practitioner_uuid, patient_uuid, billed_at, administered_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS medical_call_monitors (
    world_name TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    registered_by TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    registered_at INTEGER NOT NULL,
    PRIMARY KEY (world_name, x, y, z)
);

CREATE TABLE IF NOT EXISTS pharmacy_counters (
    world_name TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    registered_by TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    registered_at INTEGER NOT NULL,
    PRIMARY KEY (world_name, x, y, z)
);

CREATE TABLE IF NOT EXISTS chat_preferences (
    player_uuid TEXT PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
    active_channel TEXT NOT NULL CHECK (active_channel IN (
        'GLOBAL', 'LOCAL', 'MURMUR', 'DOJ', 'SENATE', 'JUDICIARY')),
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_contacts (
    player_uuid TEXT PRIMARY KEY REFERENCES players(uuid) ON DELETE CASCADE,
    reply_target_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    updated_at INTEGER NOT NULL,
    CHECK (player_uuid != reply_target_uuid)
);

CREATE TABLE IF NOT EXISTS mail_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sender_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    recipient_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    content TEXT NOT NULL CHECK (length(content) BETWEEN 1 AND 500),
    sent_at INTEGER NOT NULL,
    read_at INTEGER,
    deleted_at INTEGER,
    CHECK (sender_uuid != recipient_uuid)
);

CREATE INDEX IF NOT EXISTS idx_mail_recipient_sent
    ON mail_messages(recipient_uuid, deleted_at, sent_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS advertisements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    advertiser_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    content TEXT NOT NULL CHECK (length(content) BETWEEN 1 AND 500),
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_advertisements_advertiser_created
    ON advertisements(advertiser_uuid, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS player_homes (
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    home_name TEXT NOT NULL COLLATE NOCASE,
    world_name TEXT NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    z REAL NOT NULL,
    yaw REAL NOT NULL,
    pitch REAL NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (player_uuid, home_name)
);

CREATE TABLE IF NOT EXISTS civic_warps (
    warp_id TEXT PRIMARY KEY COLLATE NOCASE,
    world_name TEXT NOT NULL,
    x REAL NOT NULL,
    y REAL NOT NULL,
    z REAL NOT NULL,
    yaw REAL NOT NULL,
    pitch REAL NOT NULL,
    updated_by TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS friend_requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    requester_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    target_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED')),
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    responded_at INTEGER,
    CHECK (requester_uuid != target_uuid)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_friend_requests_pending_pair
    ON friend_requests(requester_uuid, target_uuid)
    WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS friendships (
    player_a_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    player_b_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (player_a_uuid, player_b_uuid),
    CHECK (player_a_uuid < player_b_uuid)
);

CREATE INDEX IF NOT EXISTS idx_friendships_b
    ON friendships(player_b_uuid, player_a_uuid);

CREATE TABLE IF NOT EXISTS marriage_proposals (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    proposer_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    target_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED', 'OFFICIATED')),
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL CHECK (expires_at > created_at),
    responded_at INTEGER,
    CHECK (proposer_uuid != target_uuid)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_marriage_proposals_pending_pair
    ON marriage_proposals(proposer_uuid, target_uuid)
    WHERE status IN ('PENDING', 'ACCEPTED');

CREATE TABLE IF NOT EXISTS marriages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    spouse_a_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    spouse_b_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    officiant_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE RESTRICT,
    married_at INTEGER NOT NULL,
    dissolved_at INTEGER,
    dissolved_by TEXT REFERENCES players(uuid) ON DELETE SET NULL,
    dissolution_reason TEXT,
    home_world TEXT,
    home_x REAL,
    home_y REAL,
    home_z REAL,
    home_yaw REAL,
    home_pitch REAL,
    CHECK (spouse_a_uuid < spouse_b_uuid),
    CHECK ((dissolved_at IS NULL AND dissolved_by IS NULL AND dissolution_reason IS NULL)
        OR (dissolved_at IS NOT NULL AND dissolution_reason IS NOT NULL)),
    CHECK ((home_world IS NULL AND home_x IS NULL AND home_y IS NULL AND home_z IS NULL
            AND home_yaw IS NULL AND home_pitch IS NULL)
        OR (home_world IS NOT NULL AND home_x IS NOT NULL AND home_y IS NOT NULL AND home_z IS NOT NULL
            AND home_yaw IS NOT NULL AND home_pitch IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_marriages_spouse_a
    ON marriages(spouse_a_uuid, dissolved_at, married_at DESC);

CREATE INDEX IF NOT EXISTS idx_marriages_spouse_b
    ON marriages(spouse_b_uuid, dissolved_at, married_at DESC);

CREATE TABLE IF NOT EXISTS marriage_preferences (
    marriage_id INTEGER NOT NULL REFERENCES marriages(id) ON DELETE CASCADE,
    player_uuid TEXT NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    partner_pvp_enabled INTEGER NOT NULL DEFAULT 0 CHECK (partner_pvp_enabled IN (0, 1)),
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (marriage_id, player_uuid)
);

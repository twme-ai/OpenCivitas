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

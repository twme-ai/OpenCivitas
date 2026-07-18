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

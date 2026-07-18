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

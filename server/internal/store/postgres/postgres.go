// Package postgres opens the shared SQL store on PostgreSQL, for servers
// that already run one or want several server processes on one database.
// The queries live in package sqldb; this package supplies the schema and
// the PostgreSQL dialect (numbered placeholders, row locks, a version table
// guarded by an advisory lock so two processes never migrate at once).
//
// Times and JSON are kept as text exactly as on SQLite, so data moves between
// the two engines unchanged and the server never reinterprets a client's
// GeoJSON. PostGIS is not needed.
package postgres

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store/sqldb"

	_ "github.com/jackc/pgx/v5/stdlib" // registers the "pgx" driver
)

// DB is the shared SQL store opened on PostgreSQL.
type DB = sqldb.DB

// Open connects with a PostgreSQL URL or keyword/value string, e.g.
// postgres://geocrew:secret@localhost/geocrew, and migrates the schema.
func Open(dsn string) (*DB, error) {
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(16)
	db.SetConnMaxIdleTime(5 * time.Minute)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("postgres: %w", err)
	}
	d, err := sqldb.New(ctx, db, dialect())
	if err != nil {
		db.Close()
		return nil, err
	}
	return d, nil
}

// lockKey is an arbitrary constant for pg_advisory_xact_lock: "GeoC" in ASCII.
const lockKey = 0x47656f43

func dialect() sqldb.Dialect {
	return sqldb.Dialect{
		Name:       "postgres",
		Numbered:   true,
		ForUpdate:  " FOR UPDATE",
		Migrations: migrations,
		Version: func(ctx context.Context, db *sql.DB) (int, error) {
			if _, err := db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS geocrew_schema (version INTEGER NOT NULL)`); err != nil {
				return 0, err
			}
			return version(ctx, db.QueryRowContext(ctx, `SELECT MAX(version) FROM geocrew_schema`))
		},
		Migrate: func(ctx context.Context, tx *sql.Tx, n int, m string) (bool, error) {
			if _, err := tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock($1)`, lockKey); err != nil {
				return false, err
			}
			// Another server may have migrated while this one waited for the lock.
			v, err := version(ctx, tx.QueryRowContext(ctx, `SELECT MAX(version) FROM geocrew_schema`))
			if err != nil || v >= n {
				return false, err
			}
			if _, err := tx.ExecContext(ctx, m); err != nil {
				return false, err
			}
			_, err = tx.ExecContext(ctx, `INSERT INTO geocrew_schema (version) VALUES ($1)`, n)
			return err == nil, err
		},
	}
}

func version(_ context.Context, r *sql.Row) (int, error) {
	var v sql.NullInt64
	if err := r.Scan(&v); err != nil && !errors.Is(err, sql.ErrNoRows) {
		return 0, err
	}
	return int(v.Int64), nil
}

// migrations mirror package sqlite's schema with PostgreSQL types: BIGINT
// for counters and flags, DOUBLE PRECISION for coordinates. The first one is
// the whole schema as of SQLite migration 8; later changes are appended to
// both lists. Never edit an applied migration.
var migrations = []string{
	`CREATE TABLE workspaces (
		id               TEXT PRIMARY KEY,
		name             TEXT NOT NULL,
		invite_code      TEXT NOT NULL UNIQUE,
		created_at       TEXT NOT NULL,
		last_seq         BIGINT NOT NULL DEFAULT 0,
		settings         TEXT NOT NULL DEFAULT '{}',
		settings_version BIGINT NOT NULL DEFAULT 0,
		comment_seq      BIGINT NOT NULL DEFAULT 0
	);
	CREATE TABLE members (
		id           TEXT PRIMARY KEY,
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		nickname     TEXT NOT NULL,
		role         TEXT NOT NULL,
		token_hash   TEXT NOT NULL UNIQUE,
		joined_at    TEXT NOT NULL,
		revoked      BIGINT NOT NULL DEFAULT 0,
		last_seen    TEXT NOT NULL DEFAULT ''
	);
	CREATE INDEX members_ws ON members(workspace_id);
	CREATE TABLE features (
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		id           TEXT NOT NULL,
		seq          BIGINT NOT NULL,
		version      BIGINT NOT NULL,
		updated_at   TEXT NOT NULL,
		updated_by   TEXT NOT NULL,
		deleted      BIGINT NOT NULL,
		geometry     TEXT NOT NULL,
		properties   TEXT NOT NULL,
		PRIMARY KEY (workspace_id, id)
	);
	CREATE UNIQUE INDEX features_seq ON features(workspace_id, seq);
	CREATE TABLE feature_history (
		workspace_id TEXT NOT NULL,
		id           TEXT NOT NULL,
		seq          BIGINT NOT NULL,
		version      BIGINT NOT NULL,
		updated_at   TEXT NOT NULL,
		updated_by   TEXT NOT NULL,
		deleted      BIGINT NOT NULL,
		geometry     TEXT NOT NULL,
		properties   TEXT NOT NULL,
		replaced_at  TEXT NOT NULL
	);
	CREATE INDEX feature_history_id ON feature_history(workspace_id, id);
	CREATE TABLE files (
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		sha256       TEXT NOT NULL,
		size         BIGINT NOT NULL,
		content_type TEXT NOT NULL,
		created_at   TEXT NOT NULL,
		created_by   TEXT NOT NULL,
		PRIMARY KEY (workspace_id, sha256)
	);
	CREATE TABLE comments (
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		id           TEXT NOT NULL,
		seq          BIGINT NOT NULL,
		feature_id   TEXT NOT NULL,
		body         TEXT NOT NULL,
		at_lng       DOUBLE PRECISION,
		at_lat       DOUBLE PRECISION,
		author_id    TEXT NOT NULL,
		created_at   TEXT NOT NULL,
		deleted      BIGINT NOT NULL DEFAULT 0,
		urgent       BIGINT NOT NULL DEFAULT 0,
		PRIMARY KEY (workspace_id, id)
	);
	CREATE UNIQUE INDEX comments_seq ON comments(workspace_id, seq);
	CREATE TABLE push_targets (
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		member_id    TEXT NOT NULL REFERENCES members(id),
		endpoint     TEXT NOT NULL,
		events       TEXT NOT NULL,
		updated_at   TEXT NOT NULL,
		p256dh       TEXT NOT NULL DEFAULT '',
		auth         TEXT NOT NULL DEFAULT '',
		PRIMARY KEY (workspace_id, member_id)
	);
	CREATE TABLE view_links (
		token_hash   TEXT PRIMARY KEY,
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		member_id    TEXT NOT NULL REFERENCES members(id),
		created_at   TEXT NOT NULL,
		expires_unix BIGINT NOT NULL
	);
	CREATE INDEX view_links_member ON view_links(member_id, expires_unix);`,
	`ALTER TABLE comments ADD COLUMN roll_call BIGINT NOT NULL DEFAULT 0;
	ALTER TABLE comments ADD COLUMN answer_to TEXT NOT NULL DEFAULT '';`,
	`ALTER TABLE comments ADD COLUMN back_by_unix BIGINT NOT NULL DEFAULT 0;
	ALTER TABLE comments ADD COLUMN back_from TEXT NOT NULL DEFAULT '';
	ALTER TABLE comments ADD COLUMN overdue_sent BIGINT NOT NULL DEFAULT 0;
	CREATE INDEX comments_outing ON comments(back_by_unix) WHERE back_by_unix > 0 AND overdue_sent = 0;`,
	`ALTER TABLE workspaces ADD COLUMN archived_at TEXT NOT NULL DEFAULT '';`,
	`ALTER TABLE view_links ADD COLUMN kind TEXT NOT NULL DEFAULT 'view';`,
	// Protocol v1 (package v1): workspaces, members with a token per device,
	// roles. It sits next to the v0 tables until the API moves to v1 (M3).
	`CREATE TABLE v1_workspaces (
		id          TEXT PRIMARY KEY,
		name        TEXT NOT NULL,
		invite_code TEXT NOT NULL UNIQUE,
		leader      TEXT NOT NULL,
		created_at  TEXT NOT NULL,
		archived_at TEXT NOT NULL DEFAULT '',
		last_seq    BIGINT NOT NULL DEFAULT 0
	);
	CREATE TABLE v1_members (
		workspace_id   TEXT NOT NULL REFERENCES v1_workspaces(id),
		id             TEXT PRIMARY KEY,
		nickname       TEXT NOT NULL,
		joined_at      TEXT NOT NULL,
		removed_at     TEXT NOT NULL DEFAULT '',
		access_version BIGINT NOT NULL DEFAULT 1
	);
	CREATE INDEX v1_members_ws ON v1_members(workspace_id, joined_at);
	CREATE TABLE v1_roles (
		workspace_id TEXT NOT NULL REFERENCES v1_workspaces(id),
		id           TEXT NOT NULL,
		name         TEXT NOT NULL,
		position     BIGINT NOT NULL,
		permissions  TEXT NOT NULL,
		version      BIGINT NOT NULL,
		PRIMARY KEY (workspace_id, id)
	);
	CREATE TABLE v1_devices (
		workspace_id TEXT NOT NULL REFERENCES v1_workspaces(id),
		id           TEXT PRIMARY KEY,
		member_id    TEXT NOT NULL REFERENCES v1_members(id),
		name         TEXT NOT NULL,
		token_hash   TEXT NOT NULL UNIQUE,
		created_at   TEXT NOT NULL,
		last_seen_at TEXT NOT NULL DEFAULT '',
		revoked_at   TEXT NOT NULL DEFAULT ''
	);
	CREATE INDEX v1_devices_member ON v1_devices(member_id);
	CREATE TABLE v1_member_roles (
		workspace_id TEXT NOT NULL REFERENCES v1_workspaces(id),
		member_id    TEXT NOT NULL REFERENCES v1_members(id),
		role_id      TEXT NOT NULL,
		PRIMARY KEY (workspace_id, member_id, role_id),
		FOREIGN KEY (workspace_id, role_id) REFERENCES v1_roles(workspace_id, id)
	);`,
}

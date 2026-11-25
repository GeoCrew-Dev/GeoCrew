// Package sqlite is the default Store backend: one SQLite file, pure Go
// driver (no CGO), suitable for teams of up to a few hundred members. The
// queries live in package sqldb; this package supplies the SQLite dialect.
package sqlite

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqldb"

	_ "modernc.org/sqlite" // registers the "sqlite" driver
)

// DB is the shared SQL store opened on SQLite.
type DB = sqldb.DB

// Open opens (or creates) the database at path and migrates it.
// Use ":memory:" for tests.
func Open(path string) (*DB, error) {
	dsn := "file:" + path + "?_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)"
	if path != ":memory:" {
		dsn += "&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)"
	}
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// SQLite has a single writer; one connection keeps transactions simple and
	// is plenty for field-team traffic. It also serialises every transaction,
	// so the dialect needs no row locks.
	db.SetMaxOpenConns(1)
	d, err := sqldb.New(context.Background(), db, dialect())
	if err != nil {
		db.Close()
		return nil, err
	}
	return d, nil
}

func dialect() sqldb.Dialect {
	return sqldb.Dialect{
		Name:       "sqlite",
		Migrations: migrations,
		Version: func(ctx context.Context, db *sql.DB) (int, error) {
			var v int
			err := db.QueryRowContext(ctx, "PRAGMA user_version").Scan(&v)
			return v, err
		},
		Migrate: func(ctx context.Context, tx *sql.Tx, n int, m string) (bool, error) {
			if _, err := tx.ExecContext(ctx, m); err != nil {
				return false, err
			}
			_, err := tx.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version = %d", n))
			return err == nil, err
		},
		// A compacted copy, safe while the server is running.
		Backup: func(ctx context.Context, db *sql.DB, dest string) error {
			_, err := db.ExecContext(ctx, `VACUUM INTO ?`, dest)
			return err
		},
	}
}

// migrations are applied in order; PRAGMA user_version records progress.
// Never edit an applied migration: append a new one here and the matching
// one to package postgres.
var migrations = []string{
	`CREATE TABLE workspaces (
		id          TEXT PRIMARY KEY,
		name        TEXT NOT NULL,
		invite_code TEXT NOT NULL UNIQUE,
		created_at  TEXT NOT NULL,
		last_seq    INTEGER NOT NULL DEFAULT 0
	);
	CREATE TABLE members (
		id           TEXT PRIMARY KEY,
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		nickname     TEXT NOT NULL,
		role         TEXT NOT NULL,
		token_hash   TEXT NOT NULL UNIQUE,
		joined_at    TEXT NOT NULL,
		revoked      INTEGER NOT NULL DEFAULT 0
	);
	CREATE INDEX members_ws ON members(workspace_id);
	CREATE TABLE features (
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		id           TEXT NOT NULL,
		seq          INTEGER NOT NULL,
		version      INTEGER NOT NULL,
		updated_at   TEXT NOT NULL,
		updated_by   TEXT NOT NULL,
		deleted      INTEGER NOT NULL,
		geometry     TEXT NOT NULL,
		properties   TEXT NOT NULL,
		PRIMARY KEY (workspace_id, id)
	);
	CREATE UNIQUE INDEX features_seq ON features(workspace_id, seq);
	CREATE TABLE feature_history (
		workspace_id TEXT NOT NULL,
		id           TEXT NOT NULL,
		seq          INTEGER NOT NULL,
		version      INTEGER NOT NULL,
		updated_at   TEXT NOT NULL,
		updated_by   TEXT NOT NULL,
		deleted      INTEGER NOT NULL,
		geometry     TEXT NOT NULL,
		properties   TEXT NOT NULL,
		replaced_at  TEXT NOT NULL
	);
	CREATE INDEX feature_history_id ON feature_history(workspace_id, id);
	CREATE TABLE files (
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		sha256       TEXT NOT NULL,
		size         INTEGER NOT NULL,
		content_type TEXT NOT NULL,
		created_at   TEXT NOT NULL,
		created_by   TEXT NOT NULL,
		PRIMARY KEY (workspace_id, sha256)
	);`,
	`ALTER TABLE workspaces ADD COLUMN settings TEXT NOT NULL DEFAULT '{}';
	ALTER TABLE workspaces ADD COLUMN settings_version INTEGER NOT NULL DEFAULT 0;`,
	`ALTER TABLE workspaces ADD COLUMN comment_seq INTEGER NOT NULL DEFAULT 0;
	CREATE TABLE comments (
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		id           TEXT NOT NULL,
		seq          INTEGER NOT NULL,
		feature_id   TEXT NOT NULL,
		body         TEXT NOT NULL,
		at_lng       REAL,
		at_lat       REAL,
		author_id    TEXT NOT NULL,
		created_at   TEXT NOT NULL,
		deleted      INTEGER NOT NULL DEFAULT 0,
		PRIMARY KEY (workspace_id, id)
	);
	CREATE UNIQUE INDEX comments_seq ON comments(workspace_id, seq);`,
	`CREATE TABLE push_targets (
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		member_id    TEXT NOT NULL REFERENCES members(id),
		endpoint     TEXT NOT NULL,
		events       TEXT NOT NULL,
		updated_at   TEXT NOT NULL,
		PRIMARY KEY (workspace_id, member_id)
	);`,
	`ALTER TABLE push_targets ADD COLUMN p256dh TEXT NOT NULL DEFAULT '';
	ALTER TABLE push_targets ADD COLUMN auth TEXT NOT NULL DEFAULT '';`,
	`ALTER TABLE members ADD COLUMN last_seen TEXT NOT NULL DEFAULT '';`,
	`ALTER TABLE comments ADD COLUMN urgent INTEGER NOT NULL DEFAULT 0;`,
	`CREATE TABLE view_links (
		token_hash   TEXT PRIMARY KEY,
		workspace_id TEXT NOT NULL REFERENCES workspaces(id),
		member_id    TEXT NOT NULL REFERENCES members(id),
		created_at   TEXT NOT NULL,
		expires_unix INTEGER NOT NULL
	);
	CREATE INDEX view_links_member ON view_links(member_id, expires_unix);`,
	`ALTER TABLE comments ADD COLUMN roll_call INTEGER NOT NULL DEFAULT 0;
	ALTER TABLE comments ADD COLUMN answer_to TEXT NOT NULL DEFAULT '';`,
	`ALTER TABLE comments ADD COLUMN back_by_unix INTEGER NOT NULL DEFAULT 0;
	ALTER TABLE comments ADD COLUMN back_from TEXT NOT NULL DEFAULT '';
	ALTER TABLE comments ADD COLUMN overdue_sent INTEGER NOT NULL DEFAULT 0;
	CREATE INDEX comments_outing ON comments(back_by_unix) WHERE back_by_unix > 0 AND overdue_sent = 0;`,
	`ALTER TABLE workspaces ADD COLUMN archived_at TEXT NOT NULL DEFAULT '';`,
	`ALTER TABLE view_links ADD COLUMN kind TEXT NOT NULL DEFAULT 'view';`,
}

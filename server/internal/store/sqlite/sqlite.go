// Package sqlite is the default Store backend: one SQLite file, pure Go
// driver (no CGO), suitable for teams of up to a few hundred members.
package sqlite

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"

	_ "modernc.org/sqlite" // registers the "sqlite" driver
)

// DB implements store.Store on SQLite.
type DB struct {
	db *sql.DB
}

var _ store.Store = (*DB)(nil)

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
	// is plenty for field-team traffic.
	db.SetMaxOpenConns(1)
	d := &DB{db: db}
	if err := d.migrate(context.Background()); err != nil {
		db.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return d, nil
}

func (d *DB) Close() error { return d.db.Close() }

// migrations are applied in order; PRAGMA user_version records progress.
// Never edit an applied migration: append a new one.
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
}

func (d *DB) migrate(ctx context.Context) error {
	var v int
	if err := d.db.QueryRowContext(ctx, "PRAGMA user_version").Scan(&v); err != nil {
		return err
	}
	for i := v; i < len(migrations); i++ {
		tx, err := d.db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, migrations[i]); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %d: %w", i+1, err)
		}
		if _, err := tx.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version = %d", i+1)); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

const timeFmt = time.RFC3339Nano

func fmtTime(t time.Time) string { return t.UTC().Format(timeFmt) }

func parseTime(s string) time.Time {
	t, _ := time.Parse(timeFmt, s)
	return t
}

func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

// inviteAlphabet avoids look-alike characters so codes can be read aloud in the field.
const inviteAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

func newInviteCode() string {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	for i := range b {
		b[i] = inviteAlphabet[int(b[i])%len(inviteAlphabet)]
	}
	return string(b)
}

func hashToken(t string) string {
	h := sha256.Sum256([]byte(t))
	return hex.EncodeToString(h[:])
}

func clean(s string, max int) (string, error) {
	s = strings.TrimSpace(s)
	if s == "" || len([]rune(s)) > max {
		return "", store.ErrInvalid
	}
	return s, nil
}

func (d *DB) insertMember(ctx context.Context, tx *sql.Tx, wsID, nickname string, role store.Role) (store.Member, string, error) {
	token := randHex(24)
	m := store.Member{ID: randHex(8), WorkspaceID: wsID, Nickname: nickname, Role: role, JoinedAt: time.Now().UTC()}
	_, err := tx.ExecContext(ctx,
		`INSERT INTO members (id, workspace_id, nickname, role, token_hash, joined_at) VALUES (?,?,?,?,?,?)`,
		m.ID, wsID, nickname, string(role), hashToken(token), fmtTime(m.JoinedAt))
	return m, token, err
}

func (d *DB) CreateWorkspace(ctx context.Context, name, nickname string) (store.Workspace, store.Member, string, error) {
	name, err := clean(name, 64)
	if err != nil {
		return store.Workspace{}, store.Member{}, "", err
	}
	nickname, err = clean(nickname, 32)
	if err != nil {
		return store.Workspace{}, store.Member{}, "", err
	}
	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return store.Workspace{}, store.Member{}, "", err
	}
	defer tx.Rollback()
	ws := store.Workspace{ID: randHex(8), Name: name, CreatedAt: time.Now().UTC(), InviteCode: newInviteCode()}
	if _, err := tx.ExecContext(ctx, `INSERT INTO workspaces (id, name, invite_code, created_at) VALUES (?,?,?,?)`,
		ws.ID, ws.Name, ws.InviteCode, fmtTime(ws.CreatedAt)); err != nil {
		return store.Workspace{}, store.Member{}, "", err
	}
	m, token, err := d.insertMember(ctx, tx, ws.ID, nickname, store.RoleLeader)
	if err != nil {
		return store.Workspace{}, store.Member{}, "", err
	}
	return ws, m, token, tx.Commit()
}

func (d *DB) Join(ctx context.Context, inviteCode, nickname string) (store.Workspace, store.Member, string, error) {
	nickname, err := clean(nickname, 32)
	if err != nil {
		return store.Workspace{}, store.Member{}, "", err
	}
	code := strings.ToUpper(strings.TrimSpace(inviteCode))
	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return store.Workspace{}, store.Member{}, "", err
	}
	defer tx.Rollback()
	ws, err := scanWorkspace(tx.QueryRowContext(ctx, wsSelect+` WHERE invite_code = ?`, code))
	if errors.Is(err, store.ErrNotFound) {
		return store.Workspace{}, store.Member{}, "", store.ErrBadInvite
	}
	if err != nil {
		return store.Workspace{}, store.Member{}, "", err
	}
	m, token, err := d.insertMember(ctx, tx, ws.ID, nickname, store.RoleMember)
	if err != nil {
		return store.Workspace{}, store.Member{}, "", err
	}
	return ws, m, token, tx.Commit()
}

const wsSelect = `SELECT id, name, invite_code, created_at, last_seq, settings_version FROM workspaces`

type rowScanner interface{ Scan(...any) error }

func scanWorkspace(r rowScanner) (store.Workspace, error) {
	var ws store.Workspace
	var created string
	err := r.Scan(&ws.ID, &ws.Name, &ws.InviteCode, &created, &ws.LastSeq, &ws.SettingsVersion)
	if errors.Is(err, sql.ErrNoRows) {
		return ws, store.ErrNotFound
	}
	ws.CreatedAt = parseTime(created)
	return ws, err
}

const memberSelect = `SELECT id, workspace_id, nickname, role, joined_at, revoked FROM members`

func scanMember(r rowScanner) (store.Member, error) {
	var m store.Member
	var role, joined string
	err := r.Scan(&m.ID, &m.WorkspaceID, &m.Nickname, &role, &joined, &m.Revoked)
	if errors.Is(err, sql.ErrNoRows) {
		return m, store.ErrNotFound
	}
	m.Role = store.Role(role)
	m.JoinedAt = parseTime(joined)
	return m, err
}

func (d *DB) Authenticate(ctx context.Context, workspaceID, token string) (store.Member, error) {
	if token == "" {
		return store.Member{}, store.ErrUnauthorized
	}
	m, err := scanMember(d.db.QueryRowContext(ctx, memberSelect+` WHERE token_hash = ?`, hashToken(token)))
	if errors.Is(err, store.ErrNotFound) || (err == nil && (m.Revoked || m.WorkspaceID != workspaceID)) {
		return store.Member{}, store.ErrUnauthorized
	}
	return m, err
}

func (d *DB) Workspace(ctx context.Context, id string) (store.Workspace, error) {
	return scanWorkspace(d.db.QueryRowContext(ctx, wsSelect+` WHERE id = ?`, id))
}

func (d *DB) Workspaces(ctx context.Context) ([]store.Workspace, error) {
	rows, err := d.db.QueryContext(ctx, wsSelect+` ORDER BY created_at, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []store.Workspace
	for rows.Next() {
		ws, err := scanWorkspace(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, ws)
	}
	return out, rows.Err()
}

func (d *DB) Settings(ctx context.Context, workspaceID string) (json.RawMessage, int64, error) {
	var raw string
	var v int64
	err := d.db.QueryRowContext(ctx, `SELECT settings, settings_version FROM workspaces WHERE id = ?`, workspaceID).Scan(&raw, &v)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, 0, store.ErrNotFound
	}
	return json.RawMessage(raw), v, err
}

func (d *DB) PutSettings(ctx context.Context, workspaceID string, settings json.RawMessage, baseVersion int64) (int64, error) {
	var obj map[string]json.RawMessage
	if len(settings) > store.MaxSettingsBytes || json.Unmarshal(settings, &obj) != nil || obj == nil {
		return 0, store.ErrInvalid
	}
	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	var cur int64
	err = tx.QueryRowContext(ctx, `SELECT settings_version FROM workspaces WHERE id = ?`, workspaceID).Scan(&cur)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, store.ErrNotFound
	} else if err != nil {
		return 0, err
	}
	if baseVersion >= 0 && baseVersion != cur {
		return 0, store.ErrStale
	}
	if _, err := tx.ExecContext(ctx, `UPDATE workspaces SET settings = ?, settings_version = ? WHERE id = ?`,
		string(settings), cur+1, workspaceID); err != nil {
		return 0, err
	}
	return cur + 1, tx.Commit()
}

// Backup writes a consistent, compacted copy of the database to dest,
// which must not exist yet. Safe while the server is running.
func (d *DB) Backup(ctx context.Context, dest string) error {
	_, err := d.db.ExecContext(ctx, `VACUUM INTO ?`, dest)
	return err
}

var _ store.Backuper = (*DB)(nil)

func (d *DB) Members(ctx context.Context, workspaceID string) ([]store.Member, error) {
	rows, err := d.db.QueryContext(ctx, memberSelect+` WHERE workspace_id = ? ORDER BY joined_at, id`, workspaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []store.Member
	for rows.Next() {
		m, err := scanMember(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (d *DB) ResetInvite(ctx context.Context, workspaceID string) (string, error) {
	code := newInviteCode()
	res, err := d.db.ExecContext(ctx, `UPDATE workspaces SET invite_code = ? WHERE id = ?`, code, workspaceID)
	if err != nil {
		return "", err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return "", store.ErrNotFound
	}
	return code, nil
}

// leaderGuard fails if changing memberID away from leader would leave no active leader.
func leaderGuard(ctx context.Context, tx *sql.Tx, workspaceID, memberID string) error {
	var n int
	err := tx.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM members WHERE workspace_id = ? AND role = 'leader' AND revoked = 0 AND id <> ?`,
		workspaceID, memberID).Scan(&n)
	if err != nil {
		return err
	}
	if n == 0 {
		return store.ErrLastLeader
	}
	return nil
}

func (d *DB) SetRole(ctx context.Context, workspaceID, memberID string, role store.Role) error {
	if !role.Valid() {
		return store.ErrInvalid
	}
	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	m, err := scanMember(tx.QueryRowContext(ctx, memberSelect+` WHERE id = ? AND workspace_id = ?`, memberID, workspaceID))
	if err != nil {
		return err
	}
	if m.Role == store.RoleLeader && role != store.RoleLeader {
		if err := leaderGuard(ctx, tx, workspaceID, memberID); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, `UPDATE members SET role = ? WHERE id = ?`, string(role), memberID); err != nil {
		return err
	}
	return tx.Commit()
}

func (d *DB) RevokeMember(ctx context.Context, workspaceID, memberID string) error {
	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	m, err := scanMember(tx.QueryRowContext(ctx, memberSelect+` WHERE id = ? AND workspace_id = ?`, memberID, workspaceID))
	if err != nil {
		return err
	}
	if m.Role == store.RoleLeader {
		if err := leaderGuard(ctx, tx, workspaceID, memberID); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, `UPDATE members SET revoked = 1 WHERE id = ?`, memberID); err != nil {
		return err
	}
	return tx.Commit()
}

const featureCols = `id, seq, version, updated_at, updated_by, deleted, geometry, properties`

func scanFeature(r rowScanner) (store.Feature, error) {
	var f store.Feature
	var updated, geom, props string
	err := r.Scan(&f.ID, &f.Seq, &f.Version, &updated, &f.UpdatedBy, &f.Deleted, &geom, &props)
	if errors.Is(err, sql.ErrNoRows) {
		return f, store.ErrNotFound
	}
	f.UpdatedAt = parseTime(updated)
	f.Geometry = []byte(geom)
	f.Properties = []byte(props)
	return f, err
}

func rawOrNull(b []byte) string {
	if len(b) == 0 {
		return "null"
	}
	return string(b)
}

func (d *DB) PushFeatures(ctx context.Context, workspaceID, memberID string, fs []store.Feature) ([]store.PushResult, int64, error) {
	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, 0, err
	}
	defer tx.Rollback()
	var seq int64
	if err := tx.QueryRowContext(ctx, `SELECT last_seq FROM workspaces WHERE id = ?`, workspaceID).Scan(&seq); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, 0, store.ErrNotFound
		}
		return nil, 0, err
	}
	now := fmtTime(time.Now())
	results := make([]store.PushResult, 0, len(fs))
	for _, f := range fs {
		f.UpdatedBy = memberID
		f.UpdatedAt = f.UpdatedAt.UTC()
		cur, err := scanFeature(tx.QueryRowContext(ctx,
			`SELECT `+featureCols+` FROM features WHERE workspace_id = ? AND id = ?`, workspaceID, f.ID))
		exists := err == nil
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			return nil, 0, err
		}
		if exists && f.SameRevision(cur) {
			results = append(results, store.PushResult{ID: f.ID, Status: store.PushDuplicate, Seq: cur.Seq})
			continue
		}
		if exists && !f.Newer(cur) {
			c := cur
			results = append(results, store.PushResult{ID: f.ID, Status: store.PushStale, Seq: cur.Seq, Current: &c})
			continue
		}
		seq++
		if exists {
			if _, err := tx.ExecContext(ctx,
				`INSERT INTO feature_history (workspace_id, `+featureCols+`, replaced_at) VALUES (?,?,?,?,?,?,?,?,?,?)`,
				workspaceID, cur.ID, cur.Seq, cur.Version, fmtTime(cur.UpdatedAt), cur.UpdatedBy, cur.Deleted,
				rawOrNull(cur.Geometry), rawOrNull(cur.Properties), now); err != nil {
				return nil, 0, err
			}
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO features (workspace_id, `+featureCols+`) VALUES (?,?,?,?,?,?,?,?,?)
			 ON CONFLICT (workspace_id, id) DO UPDATE SET
			   seq = excluded.seq, version = excluded.version, updated_at = excluded.updated_at,
			   updated_by = excluded.updated_by, deleted = excluded.deleted,
			   geometry = excluded.geometry, properties = excluded.properties`,
			workspaceID, f.ID, seq, f.Version, fmtTime(f.UpdatedAt), f.UpdatedBy, f.Deleted,
			rawOrNull(f.Geometry), rawOrNull(f.Properties)); err != nil {
			return nil, 0, err
		}
		results = append(results, store.PushResult{ID: f.ID, Status: store.PushApplied, Seq: seq})
	}
	if _, err := tx.ExecContext(ctx, `UPDATE workspaces SET last_seq = ? WHERE id = ?`, seq, workspaceID); err != nil {
		return nil, 0, err
	}
	return results, seq, tx.Commit()
}

func (d *DB) PullFeatures(ctx context.Context, workspaceID string, since int64, limit int) ([]store.Feature, bool, error) {
	rows, err := d.db.QueryContext(ctx,
		`SELECT `+featureCols+` FROM features WHERE workspace_id = ? AND seq > ? ORDER BY seq LIMIT ?`,
		workspaceID, since, limit+1)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	var out []store.Feature
	for rows.Next() {
		f, err := scanFeature(rows)
		if err != nil {
			return nil, false, err
		}
		out = append(out, f)
	}
	if err := rows.Err(); err != nil {
		return nil, false, err
	}
	more := len(out) > limit
	if more {
		out = out[:limit]
	}
	return out, more, nil
}

func (d *DB) FeatureHistory(ctx context.Context, workspaceID, featureID string) ([]store.Feature, error) {
	rows, err := d.db.QueryContext(ctx,
		`SELECT `+featureCols+` FROM feature_history WHERE workspace_id = ? AND id = ? ORDER BY seq DESC`,
		workspaceID, featureID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []store.Feature
	for rows.Next() {
		f, err := scanFeature(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

func (d *DB) PutFileMeta(ctx context.Context, workspaceID string, m store.FileMeta) error {
	_, err := d.db.ExecContext(ctx,
		`INSERT INTO files (workspace_id, sha256, size, content_type, created_at, created_by) VALUES (?,?,?,?,?,?)
		 ON CONFLICT (workspace_id, sha256) DO NOTHING`,
		workspaceID, m.SHA256, m.Size, m.ContentType, fmtTime(m.CreatedAt), m.CreatedBy)
	return err
}

func (d *DB) FileMeta(ctx context.Context, workspaceID, sha string) (store.FileMeta, error) {
	var m store.FileMeta
	var created string
	err := d.db.QueryRowContext(ctx,
		`SELECT sha256, size, content_type, created_at, created_by FROM files WHERE workspace_id = ? AND sha256 = ?`,
		workspaceID, sha).Scan(&m.SHA256, &m.Size, &m.ContentType, &created, &m.CreatedBy)
	if errors.Is(err, sql.ErrNoRows) {
		return m, store.ErrNotFound
	}
	m.CreatedAt = parseTime(created)
	return m, err
}

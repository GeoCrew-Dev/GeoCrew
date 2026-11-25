package sqldb

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
)

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

func (d *DB) insertMember(ctx context.Context, tx *Tx, wsID, nickname string, role store.Role) (store.Member, string, error) {
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

const memberSelect = `SELECT id, workspace_id, nickname, role, joined_at, revoked, last_seen FROM members`

func scanMember(r rowScanner) (store.Member, error) {
	var m store.Member
	var role, joined, seen string
	err := r.Scan(&m.ID, &m.WorkspaceID, &m.Nickname, &role, &joined, &m.Revoked, &seen)
	if errors.Is(err, sql.ErrNoRows) {
		return m, store.ErrNotFound
	}
	m.Role = store.Role(role)
	m.JoinedAt = parseTime(joined)
	if seen != "" {
		t := parseTime(seen)
		m.LastSeen = &t
	}
	return m, err
}

func (d *DB) Touch(ctx context.Context, memberID string, at time.Time) error {
	_, err := d.db.ExecContext(ctx, `UPDATE members SET last_seen = ? WHERE id = ?`, fmtTime(at.Truncate(time.Minute)), memberID)
	return err
}

func (d *DB) Authenticate(ctx context.Context, workspaceID, token string) (store.Member, error) {
	if token == "" {
		return store.Member{}, store.ErrUnauthorized
	}
	if strings.HasPrefix(token, store.ViewTokenPrefix) {
		return d.authenticateView(ctx, workspaceID, token)
	}
	m, err := scanMember(d.db.QueryRowContext(ctx, memberSelect+` WHERE token_hash = ?`, hashToken(token)))
	if errors.Is(err, store.ErrNotFound) || (err == nil && (m.Revoked || m.WorkspaceID != workspaceID)) {
		return store.Member{}, store.ErrUnauthorized
	}
	return m, err
}

func (d *DB) authenticateView(ctx context.Context, workspaceID, token string) (store.Member, error) {
	m, err := scanMember(d.db.QueryRowContext(ctx, memberSelect+` WHERE id = (
		SELECT member_id FROM view_links WHERE token_hash = ? AND workspace_id = ? AND expires_unix > ?)`,
		hashToken(token), workspaceID, time.Now().Unix()))
	if errors.Is(err, store.ErrNotFound) || (err == nil && (m.Revoked || m.WorkspaceID != workspaceID)) {
		return store.Member{}, store.ErrUnauthorized
	}
	if err != nil {
		return store.Member{}, err
	}
	m.Role = store.RoleReadonly
	m.Viewer = true
	return m, nil
}

func (d *DB) CreateViewLink(ctx context.Context, workspaceID, memberID string, expires time.Time) (string, error) {
	token := store.ViewTokenPrefix + randHex(24)
	now := time.Now()
	// Expired links of anyone in the workspace are no use to keep.
	if _, err := d.db.ExecContext(ctx, `DELETE FROM view_links WHERE workspace_id = ? AND expires_unix <= ?`, workspaceID, now.Unix()); err != nil {
		return "", err
	}
	_, err := d.db.ExecContext(ctx, `INSERT INTO view_links (token_hash, workspace_id, member_id, created_at, expires_unix) VALUES (?, ?, ?, ?, ?)`,
		hashToken(token), workspaceID, memberID, fmtTime(now), expires.Unix())
	if err != nil {
		return "", err
	}
	return token, nil
}

func (d *DB) RevokeViewLinks(ctx context.Context, workspaceID, memberID string) (int, error) {
	var n int
	if err := d.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM view_links WHERE workspace_id = ? AND member_id = ? AND expires_unix > ?`,
		workspaceID, memberID, time.Now().Unix()).Scan(&n); err != nil {
		return 0, err
	}
	_, err := d.db.ExecContext(ctx, `DELETE FROM view_links WHERE workspace_id = ? AND member_id = ?`, workspaceID, memberID)
	return n, err
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
	err = tx.QueryRowContext(ctx, `SELECT settings_version FROM workspaces WHERE id = ?`+d.dialect.ForUpdate, workspaceID).Scan(&cur)
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
func leaderGuard(ctx context.Context, tx *Tx, workspaceID, memberID string) error {
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
	// Two leaders stepping down at once must not both pass the last-leader check.
	if err := d.lockWorkspace(ctx, tx, workspaceID); err != nil {
		return err
	}
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
	// Two leaders stepping down at once must not both pass the last-leader check.
	if err := d.lockWorkspace(ctx, tx, workspaceID); err != nil {
		return err
	}
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
	// A removed member's device must stop hearing about the workspace.
	if _, err := tx.ExecContext(ctx, `DELETE FROM push_targets WHERE workspace_id = ? AND member_id = ?`, workspaceID, memberID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM view_links WHERE workspace_id = ? AND member_id = ?`, workspaceID, memberID); err != nil {
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

func (d *DB) PushFeatures(ctx context.Context, workspaceID, memberID string, fs []store.Feature, opts ...store.PushOption) ([]store.PushResult, int64, error) {
	cfg := store.NewPushConfig(opts)
	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, 0, err
	}
	defer tx.Rollback()
	var seq int64
	if err := tx.QueryRowContext(ctx, `SELECT last_seq FROM workspaces WHERE id = ?`+d.dialect.ForUpdate, workspaceID).Scan(&seq); err != nil {
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
		if len(cfg.LockedProps) > 0 && !f.Deleted {
			var prev json.RawMessage
			if exists {
				prev = cur.Properties
			}
			f.Properties = store.KeepLocked(f.Properties, prev, cfg.LockedProps)
		}
		seq++
		if exists {
			if _, err := tx.ExecContext(ctx,
				`INSERT INTO feature_history (workspace_id, `+featureCols+`, replaced_at) VALUES (?,?,?,?,?,?,?,?,?,?)`,
				workspaceID, cur.ID, cur.Seq, cur.Version, fmtTime(cur.UpdatedAt), cur.UpdatedBy, b2i(cur.Deleted),
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
			workspaceID, f.ID, seq, f.Version, fmtTime(f.UpdatedAt), f.UpdatedBy, b2i(f.Deleted),
			rawOrNull(f.Geometry), rawOrNull(f.Properties)); err != nil {
			return nil, 0, err
		}
		res := store.PushResult{ID: f.ID, Status: store.PushApplied, Seq: seq}
		if exists {
			prev := cur
			res.Previous = &prev
		}
		results = append(results, res)
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

func (d *DB) DeleteWorkspace(ctx context.Context, workspaceID string) error {
	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// Waits for pushes in flight, so none lands in a half-deleted workspace.
	if err := d.lockWorkspace(ctx, tx, workspaceID); err != nil {
		return err
	}
	// Every table but workspaces is keyed by workspace_id; children go first.
	for i := len(tables) - 1; i > 0; i-- {
		t := tables[i]
		if _, err := tx.ExecContext(ctx, `DELETE FROM `+t+` WHERE workspace_id = ?`, workspaceID); err != nil {
			return fmt.Errorf("%s: %w", t, err)
		}
	}
	res, err := tx.ExecContext(ctx, `DELETE FROM workspaces WHERE id = ?`, workspaceID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return store.ErrNotFound
	}
	return tx.Commit()
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

package sqldb

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

// V1 implements protocol v1 storage (package v1) on the same database as the
// v0 store, in tables prefixed v1_. Get one with [DB.V1].
type V1 struct {
	d   *DB
	now func() time.Time
}

var _ v1.Store = (*V1)(nil)

// V1 returns the protocol v1 store on this database.
func (d *DB) V1() *V1 { return &V1{d: d, now: time.Now} }

// WithClock returns a copy that reads the time from now, for tests.
func (v *V1) WithClock(now func() time.Time) *V1 {
	c := *v
	c.now = now
	return &c
}

func (v *V1) clock() time.Time { return v.now().UTC() }

// v1TimeFmt keeps nine fractional digits, so that in the v1 tables text order
// is time order (RFC 3339 with trailing zeros trimmed is not).
const v1TimeFmt = "2006-01-02T15:04:05.000000000Z07:00"

func fmtV1Time(t time.Time) string { return t.UTC().Format(v1TimeFmt) }

func (v *V1) CreateWorkspace(ctx context.Context, name, nickname, device string) (v1.Joined, error) {
	name, err := clean(name, 64)
	if err != nil {
		return v1.Joined{}, err
	}
	nickname, device, err = cleanJoiner(nickname, device)
	if err != nil {
		return v1.Joined{}, err
	}
	perms, err := perm.MarshalPermissions(v1.DefaultBasePermissions)
	if err != nil {
		return v1.Joined{}, err
	}
	tx, err := v.d.db.BeginTx(ctx, nil)
	if err != nil {
		return v1.Joined{}, err
	}
	defer tx.Rollback()
	at := v.clock()
	ws := v1.Workspace{ID: randHex(8), Name: name, InviteCode: newInviteCode(), Leader: randHex(8), CreatedAt: at}
	if _, err := tx.ExecContext(ctx, `INSERT INTO v1_workspaces (id, name, invite_code, leader, created_at) VALUES (?,?,?,?,?)`,
		ws.ID, ws.Name, ws.InviteCode, ws.Leader, fmtV1Time(at)); err != nil {
		return v1.Joined{}, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO v1_roles (workspace_id, id, name, position, permissions, version) VALUES (?,?,?,0,?,1)`,
		ws.ID, perm.Base, v1.BaseRoleName, string(perms)); err != nil {
		return v1.Joined{}, err
	}
	j, err := v.addMember(ctx, tx, ws, ws.Leader, nickname, device, at)
	if err != nil {
		return v1.Joined{}, err
	}
	return j, tx.Commit()
}

func (v *V1) Join(ctx context.Context, inviteCode, nickname, device string) (v1.Joined, error) {
	nickname, device, err := cleanJoiner(nickname, device)
	if err != nil {
		return v1.Joined{}, err
	}
	tx, err := v.d.db.BeginTx(ctx, nil)
	if err != nil {
		return v1.Joined{}, err
	}
	defer tx.Rollback()
	code := strings.ToUpper(strings.TrimSpace(inviteCode))
	ws, err := scanV1Workspace(tx.QueryRowContext(ctx, v1WorkspaceSelect+` WHERE invite_code = ?`, code))
	if errors.Is(err, store.ErrNotFound) {
		return v1.Joined{}, store.ErrBadInvite
	}
	if err != nil {
		return v1.Joined{}, err
	}
	if ws.ArchivedAt != nil {
		return v1.Joined{}, store.ErrArchived
	}
	j, err := v.addMember(ctx, tx, ws, randHex(8), nickname, device, v.clock())
	if err != nil {
		return v1.Joined{}, err
	}
	return j, tx.Commit()
}

func cleanJoiner(nickname, device string) (string, string, error) {
	nickname, err := clean(nickname, 32)
	if err != nil {
		return "", "", err
	}
	device, err = clean(device, 64)
	return nickname, device, err
}

// addMember adds a member with only the base role, signed in on one device.
func (v *V1) addMember(ctx context.Context, tx *Tx, ws v1.Workspace, id, nickname, device string, at time.Time) (v1.Joined, error) {
	m := v1.Member{ID: id, WorkspaceID: ws.ID, Nickname: nickname, Roles: []string{}, JoinedAt: at, AccessVersion: 1}
	if _, err := tx.ExecContext(ctx, `INSERT INTO v1_members (workspace_id, id, nickname, joined_at, access_version) VALUES (?,?,?,?,?)`,
		ws.ID, m.ID, nickname, fmtV1Time(at), m.AccessVersion); err != nil {
		return v1.Joined{}, err
	}
	token := randHex(24)
	dev := v1.Device{ID: randHex(8), MemberID: m.ID, Name: device, CreatedAt: at}
	if _, err := tx.ExecContext(ctx, `INSERT INTO v1_devices (workspace_id, id, member_id, name, token_hash, created_at) VALUES (?,?,?,?,?,?)`,
		ws.ID, dev.ID, m.ID, device, hashToken(token), fmtV1Time(at)); err != nil {
		return v1.Joined{}, err
	}
	return v1.Joined{Workspace: ws, Member: m, Device: dev, Token: token}, nil
}

const v1WorkspaceSelect = `SELECT id, name, invite_code, leader, created_at, archived_at FROM v1_workspaces`

func scanV1Workspace(r rowScanner) (v1.Workspace, error) {
	var ws v1.Workspace
	var created, archived string
	err := r.Scan(&ws.ID, &ws.Name, &ws.InviteCode, &ws.Leader, &created, &archived)
	if errors.Is(err, sql.ErrNoRows) {
		return ws, store.ErrNotFound
	}
	if err != nil {
		return ws, err
	}
	ws.CreatedAt = parseTime(created)
	if archived != "" {
		t := parseTime(archived)
		ws.ArchivedAt = &t
	}
	return ws, nil
}

func (v *V1) Authenticate(ctx context.Context, workspaceID, token string) (v1.Member, v1.Device, error) {
	if token == "" {
		return v1.Member{}, v1.Device{}, store.ErrUnauthorized
	}
	var m v1.Member
	var dev v1.Device
	var joined, created string
	err := v.d.db.QueryRowContext(ctx, `SELECT m.workspace_id, m.id, m.nickname, m.joined_at, m.access_version, d.id, d.name, d.created_at
		FROM v1_devices d JOIN v1_members m ON m.id = d.member_id
		WHERE d.token_hash = ? AND d.revoked_at = '' AND m.removed_at = ''`, hashToken(token)).
		Scan(&m.WorkspaceID, &m.ID, &m.Nickname, &joined, &m.AccessVersion, &dev.ID, &dev.Name, &created)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && m.WorkspaceID != workspaceID) {
		return v1.Member{}, v1.Device{}, store.ErrUnauthorized
	}
	if err != nil {
		return v1.Member{}, v1.Device{}, err
	}
	m.JoinedAt, dev.CreatedAt, dev.MemberID = parseTime(joined), parseTime(created), m.ID
	rows, err := v.d.db.QueryContext(ctx, `SELECT role_id FROM v1_member_roles WHERE workspace_id = ? AND member_id = ? ORDER BY role_id`, workspaceID, m.ID)
	if err != nil {
		return v1.Member{}, v1.Device{}, err
	}
	defer rows.Close()
	m.Roles = []string{}
	for rows.Next() {
		var r string
		if err := rows.Scan(&r); err != nil {
			return v1.Member{}, v1.Device{}, err
		}
		m.Roles = append(m.Roles, r)
	}
	return m, dev, rows.Err()
}

func (v *V1) Workspace(ctx context.Context, id string) (v1.Workspace, error) {
	return scanV1Workspace(v.d.db.QueryRowContext(ctx, v1WorkspaceSelect+` WHERE id = ?`, id))
}

func (v *V1) Members(ctx context.Context, workspaceID string) ([]v1.Member, error) {
	if _, err := v.Workspace(ctx, workspaceID); err != nil {
		return nil, err
	}
	roles, err := v.memberRoles(ctx, workspaceID)
	if err != nil {
		return nil, err
	}
	rows, err := v.d.db.QueryContext(ctx, `SELECT id, nickname, joined_at, access_version FROM v1_members
		WHERE workspace_id = ? AND removed_at = '' ORDER BY joined_at, id`, workspaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []v1.Member{}
	for rows.Next() {
		m := v1.Member{WorkspaceID: workspaceID}
		var joined string
		if err := rows.Scan(&m.ID, &m.Nickname, &joined, &m.AccessVersion); err != nil {
			return nil, err
		}
		m.JoinedAt = parseTime(joined)
		m.Roles = append([]string{}, roles[m.ID]...)
		out = append(out, m)
	}
	return out, rows.Err()
}

// memberRoles maps each member of the workspace to their roles besides the base role, sorted.
func (v *V1) memberRoles(ctx context.Context, workspaceID string) (map[string][]string, error) {
	rows, err := v.d.db.QueryContext(ctx, `SELECT member_id, role_id FROM v1_member_roles WHERE workspace_id = ? ORDER BY member_id, role_id`, workspaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string][]string{}
	for rows.Next() {
		var m, r string
		if err := rows.Scan(&m, &r); err != nil {
			return nil, err
		}
		out[m] = append(out[m], r)
	}
	return out, rows.Err()
}

func (v *V1) Access(ctx context.Context, workspaceID string) (perm.Workspace, error) {
	ws, err := v.Workspace(ctx, workspaceID)
	if err != nil {
		return perm.Workspace{}, err
	}
	members, err := v.Members(ctx, workspaceID)
	if err != nil {
		return perm.Workspace{}, err
	}
	out := perm.Workspace{Leader: ws.Leader, Roles: map[string]perm.Role{}, Members: map[string][]string{}, Layers: map[string]map[string]map[string]perm.Level{}}
	for _, m := range members {
		out.Members[m.ID] = m.Roles
	}
	rows, err := v.d.db.QueryContext(ctx, `SELECT id, position, permissions FROM v1_roles WHERE workspace_id = ?`, workspaceID)
	if err != nil {
		return perm.Workspace{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var id, perms string
		var r perm.Role
		if err := rows.Scan(&id, &r.Position, &perms); err != nil {
			return perm.Workspace{}, err
		}
		if err := json.Unmarshal([]byte(perms), &r.Permissions); err != nil {
			return perm.Workspace{}, err
		}
		out.Roles[id] = r
	}
	return out, rows.Err()
}

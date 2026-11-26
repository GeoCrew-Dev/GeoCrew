package sqldb

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

// begin opens a transaction on a workspace, holding its row until the end
// (PostgreSQL; SQLite runs one writer anyway), and returns what the
// permission rules need. The caller rolls back or commits.
func (v *V1) begin(ctx context.Context, workspaceID string) (*Tx, v1.Workspace, perm.Workspace, error) {
	tx, err := v.d.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, v1.Workspace{}, perm.Workspace{}, err
	}
	ws, err := scanV1Workspace(tx.QueryRowContext(ctx, v1WorkspaceSelect+` WHERE id = ?`+v.d.dialect.ForUpdate, workspaceID))
	if err != nil {
		tx.Rollback()
		return nil, v1.Workspace{}, perm.Workspace{}, err
	}
	acc, err := v.access(ctx, tx, ws)
	if err != nil {
		tx.Rollback()
		return nil, v1.Workspace{}, perm.Workspace{}, err
	}
	return tx, ws, acc, nil
}

// audit appends an entry to the workspace's audit log, numbered per workspace.
func (v *V1) audit(ctx context.Context, tx *Tx, workspaceID, by, action, target string, detail map[string]any, at time.Time) error {
	d := []byte("{}")
	if detail != nil {
		var err error
		if d, err = json.Marshal(detail); err != nil {
			return err
		}
	}
	var id int64
	if err := tx.QueryRowContext(ctx, `UPDATE v1_workspaces SET audit_seq = audit_seq + 1 WHERE id = ? RETURNING audit_seq`, workspaceID).Scan(&id); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO v1_audit (workspace_id, id, at, by_member, action, target, detail) VALUES (?,?,?,?,?,?,?)`,
		workspaceID, id, fmtV1Time(at), by, action, target, string(d))
	return err
}

func (v *V1) UpdateWorkspace(ctx context.Context, workspaceID, actor string, u v1.WorkspaceUpdate) (v1.Workspace, error) {
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return v1.Workspace{}, err
	}
	defer tx.Rollback()
	if !acc.Has(actor, perm.ManageWorkspace) {
		return v1.Workspace{}, store.ErrForbidden
	}
	if u.Name != nil {
		if ws.Name, err = clean(*u.Name, 64); err != nil {
			return v1.Workspace{}, err
		}
	}
	if u.Archived != nil {
		switch {
		case *u.Archived && ws.ArchivedAt == nil:
			at := v.clock()
			ws.ArchivedAt = &at
		case !*u.Archived:
			ws.ArchivedAt = nil
		}
	}
	archived := ""
	if ws.ArchivedAt != nil {
		archived = fmtV1Time(*ws.ArchivedAt)
	}
	if _, err := tx.ExecContext(ctx, `UPDATE v1_workspaces SET name = ?, archived_at = ? WHERE id = ?`, ws.Name, archived, ws.ID); err != nil {
		return v1.Workspace{}, err
	}
	return ws, tx.Commit()
}

func (v *V1) ResetInvite(ctx context.Context, workspaceID, actor string) (string, error) {
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	if !acc.Has(actor, perm.ManageMembers) {
		return "", store.ErrForbidden
	}
	code, err := freeInviteCode(ctx, tx)
	if err != nil {
		return "", err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE v1_workspaces SET invite_code = ? WHERE id = ?`, code, ws.ID); err != nil {
		return "", err
	}
	return code, tx.Commit()
}

func (v *V1) SetNickname(ctx context.Context, workspaceID, member, nickname string) (v1.Member, error) {
	nickname, err := clean(nickname, 32)
	if err != nil {
		return v1.Member{}, err
	}
	res, err := v.d.db.ExecContext(ctx, `UPDATE v1_members SET nickname = ? WHERE workspace_id = ? AND id = ? AND removed_at = ''`,
		nickname, workspaceID, member)
	if err != nil {
		return v1.Member{}, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return v1.Member{}, store.ErrNotFound
	}
	return v.member(ctx, v.d.db, workspaceID, member)
}

func (v *V1) RemoveMember(ctx context.Context, workspaceID, actor, target string) error {
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, ok := acc.Members[target]; !ok {
		return store.ErrNotFound
	}
	action := v1.AuditRemoveMember
	switch {
	case actor == target && target == ws.Leader:
		return v1.ErrLeaderMustTransfer
	case actor == target:
		action = v1.AuditLeave // a safety right: no setting can take it away
	case !acc.CanManageMember(actor, target):
		return store.ErrForbidden
	}
	at := fmtV1Time(v.clock())
	for _, q := range []string{
		`UPDATE v1_members SET removed_at = ? WHERE workspace_id = ? AND id = ?`,
		`UPDATE v1_devices SET revoked_at = ? WHERE workspace_id = ? AND member_id = ? AND revoked_at = ''`,
		`UPDATE v1_transfer_codes SET used_at = ? WHERE workspace_id = ? AND member_id = ? AND used_at = ''`,
	} {
		if _, err := tx.ExecContext(ctx, q, at, ws.ID, target); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM v1_member_roles WHERE workspace_id = ? AND member_id = ?`, ws.ID, target); err != nil {
		return err
	}
	if err := v.audit(ctx, tx, ws.ID, actor, action, target, nil, parseTime(at)); err != nil {
		return err
	}
	return tx.Commit()
}

func (v *V1) TransferLeader(ctx context.Context, workspaceID, actor, to string) error {
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if actor != ws.Leader {
		return store.ErrForbidden
	}
	if _, ok := acc.Members[to]; !ok {
		return store.ErrNotFound
	}
	if to == ws.Leader {
		return store.ErrInvalid
	}
	if _, err := tx.ExecContext(ctx, `UPDATE v1_workspaces SET leader = ? WHERE id = ?`, to, ws.ID); err != nil {
		return err
	}
	// Both now may do something else (protocol 10.2).
	if _, err := tx.ExecContext(ctx, `UPDATE v1_members SET access_version = access_version + 1 WHERE workspace_id = ? AND id IN (?, ?)`,
		ws.ID, actor, to); err != nil {
		return err
	}
	if err := v.audit(ctx, tx, ws.ID, actor, v1.AuditTransferLeader, to, nil, v.clock()); err != nil {
		return err
	}
	return tx.Commit()
}

func (v *V1) Devices(ctx context.Context, workspaceID, actor, member string) ([]v1.Device, error) {
	acc, err := v.Access(ctx, workspaceID)
	if err != nil {
		return nil, err
	}
	if _, ok := acc.Members[member]; !ok {
		return nil, store.ErrNotFound
	}
	if actor != member && !acc.CanManageMember(actor, member) {
		return nil, store.ErrForbidden
	}
	rows, err := v.d.db.QueryContext(ctx, `SELECT id, name, created_at, last_seen_at FROM v1_devices
		WHERE workspace_id = ? AND member_id = ? AND revoked_at = '' ORDER BY created_at, id`, workspaceID, member)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []v1.Device{}
	for rows.Next() {
		d := v1.Device{MemberID: member}
		var created, seen string
		if err := rows.Scan(&d.ID, &d.Name, &created, &seen); err != nil {
			return nil, err
		}
		d.CreatedAt = parseTime(created)
		if seen != "" {
			t := parseTime(seen)
			d.LastSeenAt = &t
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (v *V1) RevokeDevice(ctx context.Context, workspaceID, actor, deviceID string) error {
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var owner string
	err = tx.QueryRowContext(ctx, `SELECT member_id FROM v1_devices WHERE workspace_id = ? AND id = ? AND revoked_at = ''`, ws.ID, deviceID).Scan(&owner)
	if errors.Is(err, sql.ErrNoRows) {
		return store.ErrNotFound
	}
	if err != nil {
		return err
	}
	if actor != owner && !acc.CanManageMember(actor, owner) {
		return store.ErrForbidden
	}
	at := v.clock()
	if _, err := tx.ExecContext(ctx, `UPDATE v1_devices SET revoked_at = ? WHERE id = ?`, fmtV1Time(at), deviceID); err != nil {
		return err
	}
	if err := v.audit(ctx, tx, ws.ID, actor, v1.AuditRevokeDevice, deviceID, map[string]any{"member": owner}, at); err != nil {
		return err
	}
	return tx.Commit()
}

func (v *V1) Touch(ctx context.Context, deviceID string, at time.Time) error {
	_, err := v.d.db.ExecContext(ctx, `UPDATE v1_devices SET last_seen_at = ? WHERE id = ?`, fmtV1Time(at.Truncate(time.Minute)), deviceID)
	return err
}

// newTransferCode draws 12 characters from the invite alphabet (about 60
// bits): a code signs in as a member, so it is longer than an invite code.
func newTransferCode() string {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	for i := range b {
		b[i] = inviteAlphabet[int(b[i])%len(inviteAlphabet)]
	}
	return string(b)
}

// normalizeCode drops spaces and dashes and ignores case, as people type codes.
func normalizeCode(code string) string {
	return strings.ToUpper(strings.NewReplacer(" ", "", "-", "", "\t", "").Replace(code))
}

func (v *V1) CreateTransferCode(ctx context.Context, workspaceID, actor, member string) (string, time.Time, error) {
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return "", time.Time{}, err
	}
	defer tx.Rollback()
	if _, ok := acc.Members[member]; !ok {
		return "", time.Time{}, store.ErrNotFound
	}
	// Whoever holds a code becomes the member, so only they and the leader may make one.
	if actor != member && actor != ws.Leader {
		return "", time.Time{}, store.ErrForbidden
	}
	at := v.clock()
	expires := at.Add(v1.TransferCodeTTL)
	code := newTransferCode()
	if _, err := tx.ExecContext(ctx, `UPDATE v1_transfer_codes SET used_at = ? WHERE workspace_id = ? AND member_id = ? AND used_at = ''`,
		fmtV1Time(at), ws.ID, member); err != nil {
		return "", time.Time{}, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO v1_transfer_codes (workspace_id, code_hash, member_id, created_by, created_at, expires_at) VALUES (?,?,?,?,?,?)`,
		ws.ID, hashToken(code), member, actor, fmtV1Time(at), fmtV1Time(expires)); err != nil {
		return "", time.Time{}, err
	}
	if err := v.audit(ctx, tx, ws.ID, actor, v1.AuditTransferCode, member, nil, at); err != nil {
		return "", time.Time{}, err
	}
	return code, expires, tx.Commit()
}

func (v *V1) JoinWithTransferCode(ctx context.Context, code, device string) (v1.Joined, error) {
	device, err := clean(device, 64)
	if err != nil {
		return v1.Joined{}, err
	}
	tx, err := v.d.db.BeginTx(ctx, nil)
	if err != nil {
		return v1.Joined{}, err
	}
	defer tx.Rollback()
	at := v.clock()
	hash := hashToken(normalizeCode(code))
	var wsID, memberID string
	err = tx.QueryRowContext(ctx, `SELECT workspace_id, member_id FROM v1_transfer_codes WHERE code_hash = ?`, hash).Scan(&wsID, &memberID)
	if errors.Is(err, sql.ErrNoRows) {
		return v1.Joined{}, store.ErrBadInvite
	}
	if err != nil {
		return v1.Joined{}, err
	}
	// Claiming the code in one statement keeps two phones from both using it.
	res, err := tx.ExecContext(ctx, `UPDATE v1_transfer_codes SET used_at = ? WHERE code_hash = ? AND used_at = '' AND expires_at > ?`,
		fmtV1Time(at), hash, fmtV1Time(at))
	if err != nil {
		return v1.Joined{}, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return v1.Joined{}, store.ErrBadInvite
	}
	ws, err := scanV1Workspace(tx.QueryRowContext(ctx, v1WorkspaceSelect+` WHERE id = ?`, wsID))
	if err != nil {
		return v1.Joined{}, err
	}
	m, err := v.member(ctx, tx, wsID, memberID)
	if errors.Is(err, store.ErrNotFound) {
		return v1.Joined{}, store.ErrBadInvite
	}
	if err != nil {
		return v1.Joined{}, err
	}
	token := randHex(24)
	dev := v1.Device{ID: randHex(8), MemberID: m.ID, Name: device, CreatedAt: at}
	if _, err := tx.ExecContext(ctx, `INSERT INTO v1_devices (workspace_id, id, member_id, name, token_hash, created_at) VALUES (?,?,?,?,?,?)`,
		ws.ID, dev.ID, m.ID, device, hashToken(token), fmtV1Time(at)); err != nil {
		return v1.Joined{}, err
	}
	if err := v.audit(ctx, tx, ws.ID, m.ID, v1.AuditTransferUsed, dev.ID, map[string]any{"device": device}, at); err != nil {
		return v1.Joined{}, err
	}
	return v1.Joined{Workspace: ws, Member: m, Device: dev, Token: token}, tx.Commit()
}

func (v *V1) Audit(ctx context.Context, workspaceID, actor string, before int64, limit int) ([]v1.AuditEntry, error) {
	ws, err := v.Workspace(ctx, workspaceID)
	if err != nil {
		return nil, err
	}
	if actor != ws.Leader {
		return nil, store.ErrForbidden
	}
	if limit <= 0 || limit > 100 {
		limit = 100
	}
	if before <= 0 {
		before = 1<<63 - 1
	}
	rows, err := v.d.db.QueryContext(ctx, `SELECT id, at, by_member, action, target, detail FROM v1_audit
		WHERE workspace_id = ? AND id < ? ORDER BY id DESC LIMIT ?`, workspaceID, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []v1.AuditEntry{}
	for rows.Next() {
		var e v1.AuditEntry
		var at, detail string
		if err := rows.Scan(&e.ID, &at, &e.By, &e.Action, &e.Target, &detail); err != nil {
			return nil, err
		}
		e.At, e.Detail = parseTime(at), json.RawMessage(detail)
		out = append(out, e)
	}
	return out, rows.Err()
}

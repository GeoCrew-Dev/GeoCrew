package sqlite

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

func (d *DB) SetPushTarget(ctx context.Context, workspaceID string, t store.PushTarget) error {
	events, err := json.Marshal(t.Events)
	if err != nil {
		return err
	}
	if t.UpdatedAt.IsZero() {
		t.UpdatedAt = time.Now()
	}
	_, err = d.db.ExecContext(ctx,
		`INSERT INTO push_targets (workspace_id, member_id, endpoint, events, updated_at) VALUES (?,?,?,?,?)
		 ON CONFLICT (workspace_id, member_id) DO UPDATE SET
		   endpoint = excluded.endpoint, events = excluded.events, updated_at = excluded.updated_at`,
		workspaceID, t.MemberID, t.Endpoint, string(events), fmtTime(t.UpdatedAt))
	return err
}

func scanPushTarget(r rowScanner) (store.PushTarget, error) {
	var t store.PushTarget
	var events, updated string
	if err := r.Scan(&t.MemberID, &t.Endpoint, &events, &updated); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return t, store.ErrNotFound
		}
		return t, err
	}
	if err := json.Unmarshal([]byte(events), &t.Events); err != nil {
		return t, err
	}
	t.UpdatedAt = parseTime(updated)
	return t, nil
}

const pushSelect = `SELECT p.member_id, p.endpoint, p.events, p.updated_at FROM push_targets p
	JOIN members m ON m.id = p.member_id`

func (d *DB) PushTarget(ctx context.Context, workspaceID, memberID string) (store.PushTarget, error) {
	return scanPushTarget(d.db.QueryRowContext(ctx,
		pushSelect+` WHERE p.workspace_id = ? AND p.member_id = ? AND m.revoked = 0`, workspaceID, memberID))
}

func (d *DB) DeletePushTarget(ctx context.Context, workspaceID, memberID string) error {
	_, err := d.db.ExecContext(ctx, `DELETE FROM push_targets WHERE workspace_id = ? AND member_id = ?`, workspaceID, memberID)
	return err
}

func (d *DB) PushTargets(ctx context.Context, workspaceID string) ([]store.PushTarget, error) {
	rows, err := d.db.QueryContext(ctx,
		pushSelect+` WHERE p.workspace_id = ? AND m.revoked = 0 ORDER BY p.member_id`, workspaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []store.PushTarget
	for rows.Next() {
		t, err := scanPushTarget(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

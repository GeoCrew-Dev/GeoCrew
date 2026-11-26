package sqldb

import (
	"context"
	"errors"
	"fmt"
	"strings"
)

// tables in an order that satisfies the foreign keys; every one after
// workspaces has a workspace_id column (DeleteWorkspace relies on both).
var tables = []string{
	"workspaces", "members", "features", "feature_history", "files",
	"comments", "push_targets", "view_links",
}

// v1Tables are protocol v1's (package v1), in foreign-key order too; every
// one after v1_workspaces has a workspace_id column.
var v1Tables = []string{
	"v1_workspaces", "v1_members", "v1_roles", "v1_devices", "v1_member_roles",
	"v1_transfer_codes", "v1_audit", "v1_layers", "v1_layer_overrides",
}

// ErrNotEmpty means a copy's target already holds workspaces.
var ErrNotEmpty = errors.New("目标数据库里已经有工作组，只能复制到空库")

// CopyTo copies every row into dst, which must be migrated and hold no
// workspace yet, e.g. to move a server from SQLite to PostgreSQL. Columns are
// matched by name, so engines whose migrations add them in another order
// still line up. The source is read in one transaction (a consistent
// snapshot) and the target written in one, so a failed copy leaves dst empty.
// Returns the rows copied per table.
func (d *DB) CopyTo(ctx context.Context, dst *DB) (map[string]int64, error) {
	for _, t := range []string{"workspaces", "v1_workspaces"} {
		var n int
		if err := dst.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM `+t).Scan(&n); err != nil {
			return nil, err
		}
		if n > 0 {
			return nil, ErrNotEmpty
		}
	}
	src, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer src.Rollback()
	tx, err := dst.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	counts := make(map[string]int64, len(tables)+len(v1Tables))
	for _, t := range append(append([]string{}, tables...), v1Tables...) {
		c, err := copyTable(ctx, src, tx, t)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", t, err)
		}
		counts[t] = c
	}
	return counts, tx.Commit()
}

func copyTable(ctx context.Context, src, dst *Tx, table string) (int64, error) {
	rows, err := src.QueryContext(ctx, `SELECT * FROM `+table)
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	cols, err := rows.Columns()
	if err != nil {
		return 0, err
	}
	insert := `INSERT INTO ` + table + ` (` + strings.Join(cols, ", ") + `) VALUES (` +
		strings.TrimSuffix(strings.Repeat("?, ", len(cols)), ", ") + `)`
	vals := make([]any, len(cols))
	ptrs := make([]any, len(cols))
	for i := range vals {
		ptrs[i] = &vals[i]
	}
	var n int64
	for rows.Next() {
		if err := rows.Scan(ptrs...); err != nil {
			return n, err
		}
		for i, v := range vals {
			// Some drivers hand TEXT back as bytes; store it as text again.
			if b, ok := v.([]byte); ok {
				vals[i] = string(b)
			}
		}
		if _, err := dst.ExecContext(ctx, insert, vals...); err != nil {
			return n, err
		}
		n++
	}
	return n, rows.Err()
}

package sqldb

import (
	"context"
	"database/sql"
	"errors"
	"slices"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

const commentSelect = `SELECT c.id, c.seq, c.feature_id, c.body, c.at_lng, c.at_lat, c.author_id,
	COALESCE(m.nickname, ''), c.created_at, c.deleted, c.urgent, c.roll_call, c.answer_to,
	c.back_by_unix, c.back_from
	FROM comments c LEFT JOIN members m ON m.id = c.author_id`

func scanComment(r rowScanner) (store.Comment, error) {
	var c store.Comment
	var lng, lat sql.NullFloat64
	var created string
	var backBy int64
	err := r.Scan(&c.ID, &c.Seq, &c.FeatureID, &c.Body, &lng, &lat, &c.AuthorID, &c.Nickname, &created, &c.Deleted, &c.Urgent, &c.RollCall, &c.AnswerTo,
		&backBy, &c.BackFrom)
	if backBy > 0 {
		t := time.Unix(backBy, 0).UTC()
		c.BackBy = &t
	}
	if errors.Is(err, sql.ErrNoRows) {
		return c, store.ErrNotFound
	}
	if lng.Valid && lat.Valid {
		c.At = []float64{lng.Float64, lat.Float64}
	}
	c.CreatedAt = parseTime(created)
	return c, err
}

// Comment returns one comment, deleted ones included.
func (d *DB) Comment(ctx context.Context, workspaceID, commentID string) (store.Comment, error) {
	return d.comment(ctx, d.db, workspaceID, commentID)
}

func (d *DB) comment(ctx context.Context, q interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}, workspaceID, id string) (store.Comment, error) {
	return scanComment(q.QueryRowContext(ctx, commentSelect+` WHERE c.workspace_id = ? AND c.id = ?`, workspaceID, id))
}

// nextCommentSeq bumps and returns the workspace's comment counter.
func nextCommentSeq(ctx context.Context, tx *Tx, workspaceID string) (int64, error) {
	var seq int64
	err := tx.QueryRowContext(ctx,
		`UPDATE workspaces SET comment_seq = comment_seq + 1 WHERE id = ? RETURNING comment_seq`, workspaceID).Scan(&seq)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, store.ErrNotFound
	}
	return seq, err
}

func (d *DB) AddComment(ctx context.Context, workspaceID, authorID string, c store.Comment) (store.Comment, error) {
	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return c, err
	}
	defer tx.Rollback()
	cur, err := d.comment(ctx, tx, workspaceID, c.ID)
	switch {
	case err == nil:
		if cur.AuthorID == authorID && cur.FeatureID == c.FeatureID && cur.Body == c.Body &&
			!cur.Deleted && cur.Urgent == c.Urgent && cur.RollCall == c.RollCall && cur.AnswerTo == c.AnswerTo &&
			unixOf(cur.BackBy) == unixOf(c.BackBy) && cur.BackFrom == c.BackFrom &&
			slices.Equal(cur.At, c.At) {
			return cur, nil
		}
		return cur, store.ErrConflict
	case !errors.Is(err, store.ErrNotFound):
		return c, err
	}
	if c.AnswerTo != "" {
		// An answer must point at a live roll call of this workspace.
		target, err := d.comment(ctx, tx, workspaceID, c.AnswerTo)
		if errors.Is(err, store.ErrNotFound) || err == nil && (target.Deleted || !target.RollCall) {
			return c, store.ErrInvalid
		}
		if err != nil {
			return c, err
		}
	}
	if c.BackFrom != "" {
		// A report back must point at a live outing of this workspace.
		target, err := d.comment(ctx, tx, workspaceID, c.BackFrom)
		if errors.Is(err, store.ErrNotFound) || err == nil && (target.Deleted || target.BackBy == nil) {
			return c, store.ErrInvalid
		}
		if err != nil {
			return c, err
		}
	}
	seq, err := nextCommentSeq(ctx, tx, workspaceID)
	if err != nil {
		return c, err
	}
	var lng, lat any
	if len(c.At) == 2 {
		lng, lat = c.At[0], c.At[1]
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO comments (workspace_id, id, seq, feature_id, body, at_lng, at_lat, author_id, created_at, urgent, roll_call, answer_to,
		 back_by_unix, back_from)
		 VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		workspaceID, c.ID, seq, c.FeatureID, c.Body, lng, lat, authorID, fmtTime(time.Now()), b2i(c.Urgent), b2i(c.RollCall), c.AnswerTo,
		unixOf(c.BackBy), c.BackFrom); err != nil {
		return c, err
	}
	out, err := d.comment(ctx, tx, workspaceID, c.ID)
	if err != nil {
		return c, err
	}
	return out, tx.Commit()
}

func (d *DB) Comments(ctx context.Context, workspaceID string, since int64, limit int) ([]store.Comment, bool, error) {
	rows, err := d.db.QueryContext(ctx,
		commentSelect+` WHERE c.workspace_id = ? AND c.seq > ? ORDER BY c.seq LIMIT ?`, workspaceID, since, limit+1)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	out := []store.Comment{}
	for rows.Next() {
		c, err := scanComment(rows)
		if err != nil {
			return nil, false, err
		}
		out = append(out, c)
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

func (d *DB) DeleteComment(ctx context.Context, workspaceID, commentID, memberID string, byLeader bool) (store.Comment, error) {
	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return store.Comment{}, err
	}
	defer tx.Rollback()
	cur, err := d.comment(ctx, tx, workspaceID, commentID)
	if err != nil {
		return cur, err
	}
	if cur.AuthorID != memberID && !byLeader {
		return cur, store.ErrForbidden
	}
	if cur.Deleted {
		return cur, nil
	}
	seq, err := nextCommentSeq(ctx, tx, workspaceID)
	if err != nil {
		return cur, err
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE comments SET body = '', at_lng = NULL, at_lat = NULL, deleted = 1, urgent = 0, roll_call = 0, answer_to = '',
		 back_by_unix = 0, back_from = '', seq = ? WHERE workspace_id = ? AND id = ?`,
		seq, workspaceID, commentID); err != nil {
		return cur, err
	}
	out, err := d.comment(ctx, tx, workspaceID, commentID)
	if err != nil {
		return cur, err
	}
	return out, tx.Commit()
}

func unixOf(t *time.Time) int64 {
	if t == nil {
		return 0
	}
	return t.Unix()
}

func (d *DB) DueOutings(ctx context.Context, now time.Time, limit int) ([]store.DueOuting, error) {
	rows, err := d.db.QueryContext(ctx,
		`SELECT c.workspace_id, `+strings.TrimPrefix(commentSelect, "SELECT ")+`
		 WHERE c.back_by_unix > 0 AND c.back_by_unix <= ? AND c.overdue_sent = 0 AND c.deleted = 0
		 AND NOT EXISTS (SELECT 1 FROM comments r WHERE r.workspace_id = c.workspace_id AND r.back_from = c.id AND r.deleted = 0)
		 ORDER BY c.back_by_unix LIMIT ?`, now.Unix(), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []store.DueOuting
	for rows.Next() {
		var ws string
		c, err := scanComment(prefixed{rows, &ws})
		if err != nil {
			return nil, err
		}
		out = append(out, store.DueOuting{WorkspaceID: ws, Comment: c})
	}
	return out, rows.Err()
}

func (d *DB) MarkOverdue(ctx context.Context, workspaceID, commentID string) error {
	_, err := d.db.ExecContext(ctx, `UPDATE comments SET overdue_sent = 1 WHERE workspace_id = ? AND id = ?`, workspaceID, commentID)
	return err
}

// prefixed scans one leading column into first, then the rest as usual.
type prefixed struct {
	r     rowScanner
	first *string
}

func (p prefixed) Scan(dest ...any) error { return p.r.Scan(append([]any{p.first}, dest...)...) }

package sqldb

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"sort"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/merge"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

func (v *V1) Pull(ctx context.Context, workspaceID, actor string, since int64, limit int, onlyLayers []string) (v1.Pulled, error) {
	if limit <= 0 || limit > v1.MaxPull {
		limit = v1.MaxPull
	}
	acc, err := v.Access(ctx, workspaceID)
	if err != nil {
		return v1.Pulled{}, err
	}
	if _, ok := acc.Members[actor]; !ok {
		return v1.Pulled{}, store.ErrForbidden
	}
	var last int64
	if err := v.d.db.QueryRowContext(ctx, `SELECT last_seq FROM v1_workspaces WHERE id = ?`, workspaceID).Scan(&last); err != nil {
		return v1.Pulled{}, err
	}
	// The page ends after limit layers and rows. Everything is then read up
	// to that seq only: something changed meanwhile has a later seq and comes
	// in the next page, so a page is consistent without a snapshot.
	upto, more := last, false
	seqs, err := v.int64s(ctx, `SELECT seq FROM (SELECT seq FROM v1_layers WHERE workspace_id = ? AND seq > ?
		UNION ALL SELECT seq FROM v1_rows WHERE workspace_id = ? AND seq > ?) AS changed ORDER BY seq LIMIT ?`,
		workspaceID, since, workspaceID, since, limit+1)
	if err != nil {
		return v1.Pulled{}, err
	}
	if len(seqs) > limit {
		upto, more = seqs[limit-1], true
	}
	layers, err := v.layerMap(ctx, v.d.db, workspaceID)
	if err != nil {
		return v1.Pulled{}, err
	}
	only := map[string]bool{}
	for _, l := range onlyLayers {
		only[l] = true
	}
	wanted := func(layer string) bool { return len(only) == 0 || only[layer] }
	sees := func(layer string) bool {
		l, ok := layers[layer]
		return ok && acc.Level(actor, perm.ViewLayer, layer) > perm.None && !isDeleted(l)
	}
	out := v1.Pulled{Layers: []v1.Layer{}, Rows: []v1.Row{}, Conflicts: []v1.Conflict{}, Seq: upto, More: more}
	for _, l := range layers {
		// Deleted layers come too, so clients hide them; hidden ones do not.
		if l.Seq > since && l.Seq <= upto && acc.Level(actor, perm.ViewLayer, l.ID) > perm.None && wanted(l.ID) {
			out.Layers = append(out.Layers, l)
		}
	}
	sortLayers(out.Layers)

	rows, err := v.d.db.QueryContext(ctx, `SELECT `+rowCols+` FROM v1_rows WHERE workspace_id = ? AND seq > ? AND seq <= ? ORDER BY seq`,
		workspaceID, since, upto)
	if err != nil {
		return v1.Pulled{}, err
	}
	defer rows.Close()
	for rows.Next() {
		r, err := scanRow(rows)
		if err != nil {
			return v1.Pulled{}, err
		}
		if r.Layer == v1.TableAttachments {
			// An attachment goes where its target layer goes (protocol 9.1).
			if target := jsonString(r.Fields["target_layer"].Value); sees(target) && wanted(target) {
				out.Rows = append(out.Rows, r)
			}
			continue
		}
		if !sees(r.Layer) || !wanted(r.Layer) {
			continue
		}
		r.Fields = liveFields(layers[r.Layer], r.Fields) // a deleted field is no longer returned (protocol 7.3)
		out.Rows = append(out.Rows, r)
	}
	if err := rows.Err(); err != nil {
		return v1.Pulled{}, err
	}

	// Read the conflicts first: SQLite runs on one connection, so the lookups
	// below cannot happen while this cursor is open.
	var conflicts []v1.Conflict
	cs, err := v.d.db.QueryContext(ctx, `SELECT `+conflictCols+` FROM v1_conflicts WHERE workspace_id = ? AND update_seq > ? AND update_seq <= ?
		ORDER BY update_seq, id`, workspaceID, since, upto)
	if err != nil {
		return v1.Pulled{}, err
	}
	for cs.Next() {
		c, err := scanConflict(cs)
		if err != nil {
			cs.Close()
			return v1.Pulled{}, err
		}
		conflicts = append(conflicts, c)
	}
	cs.Close()
	if err := cs.Err(); err != nil {
		return v1.Pulled{}, err
	}
	for _, c := range conflicts {
		layer := c.Layer
		switch layer {
		case v1.TableLayers:
			layer = c.Row
		case v1.TableAttachments:
			if layer, err = v.attachmentTarget(ctx, workspaceID, c.Row); err != nil {
				return v1.Pulled{}, err
			}
		}
		if sees(layer) && wanted(layer) {
			out.Conflicts = append(out.Conflicts, c)
		}
	}
	return out, nil
}

// attachmentTarget reads which layer an attachment row belongs to.
func (v *V1) attachmentTarget(ctx context.Context, workspaceID, id string) (string, error) {
	r, err := scanRow(v.d.db.QueryRowContext(ctx, `SELECT `+rowCols+` FROM v1_rows WHERE workspace_id = ? AND id = ?`, workspaceID, id))
	if errors.Is(err, store.ErrNotFound) {
		return "", nil
	}
	return jsonString(r.Fields["target_layer"].Value), err
}

func (v *V1) int64s(ctx context.Context, q string, args ...any) ([]int64, error) {
	rows, err := v.d.db.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []int64
	for rows.Next() {
		var n int64
		if err := rows.Scan(&n); err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

func sortLayers(ls []v1.Layer) { sort.Slice(ls, func(i, j int) bool { return ls[i].Seq < ls[j].Seq }) }

// liveFields keeps the fields the layer still has, and its geometry.
func liveFields(l v1.Layer, fields map[string]merge.Field) map[string]merge.Field {
	live := map[string]bool{"geom": l.GeometryType != "None"}
	for _, f := range l.Fields {
		live[f.ID] = true
	}
	out := map[string]merge.Field{}
	for id, f := range fields {
		if live[id] {
			out[id] = f
		}
	}
	return out
}

// newUUID makes a random (version 4) UUID for a change the server makes itself.
func newUUID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	b[6], b[8] = b[6]&0x0f|0x40, b[8]&0x3f|0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func (v *V1) ResolveConflict(ctx context.Context, workspaceID, actor, id, choose string) (v1.Conflict, *v1.Result, error) {
	if choose != v1.ChooseKept && choose != v1.ChooseLost {
		return v1.Conflict{}, nil, store.ErrInvalid
	}
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return v1.Conflict{}, nil, err
	}
	defer tx.Rollback()
	c, err := scanConflict(tx.QueryRowContext(ctx, `SELECT `+conflictCols+` FROM v1_conflicts WHERE workspace_id = ? AND id = ?`, ws.ID, id))
	if errors.Is(err, sql.ErrNoRows) {
		return v1.Conflict{}, nil, store.ErrNotFound
	}
	if err != nil {
		return v1.Conflict{}, nil, err
	}
	// The leader and the two sides decide; nobody else may choose for them.
	party := actor == ws.Leader || (c.Kept != nil && c.Kept.By == actor) || (c.Lost != nil && c.Lost.By == actor)
	if _, member := acc.Members[actor]; !member || !party {
		return v1.Conflict{}, nil, store.ErrForbidden
	}
	if c.Resolved {
		return c, nil, nil
	}
	var res *v1.Result
	var seq int64
	if choose == v1.ChooseLost {
		if c.Kind == merge.KindDeletedField || c.Lost == nil {
			return v1.Conflict{}, nil, store.ErrInvalid // the field is gone: the value can only be read
		}
		now := v.clock()
		ch := v1.Change{ID: newUUID(), Layer: c.Layer, Row: c.Row, BaseSeq: c.Seq}
		if c.Kind == merge.KindValue {
			ch.Set = map[string]merge.Edit{c.Field: {Value: c.Lost.Value, EditedAt: now}}
		} else { // a delete that lost: delete again
			ch.Delete = &merge.Edit{Value: json.RawMessage("true"), EditedAt: now}
		}
		layers, err := v.layerMap(ctx, tx, ws.ID)
		if err != nil {
			return v1.Conflict{}, nil, err
		}
		p := push{v: v, tx: tx, ws: ws, acc: acc, layers: layers, actor: actor, clientNow: now, serverNow: now}
		r, err := p.one(ctx, ch)
		if err != nil {
			return v1.Conflict{}, nil, err
		}
		if r.Status != v1.Applied {
			return c, &r, nil // refused: the conflict stays as it is
		}
		res, seq = &r, r.Seq
	} else if seq, err = nextSeq(ctx, tx, ws.ID); err != nil {
		return v1.Conflict{}, nil, err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE v1_conflicts SET resolved = 1, update_seq = ? WHERE workspace_id = ? AND id = ?`, seq, ws.ID, id); err != nil {
		return v1.Conflict{}, nil, err
	}
	if c.Layer != v1.TableLayers {
		// The row is conflicted while any of its conflicts is open; pulls see the
		// flag change through the row's new seq.
		var open int
		if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM v1_conflicts WHERE workspace_id = ? AND row_id = ? AND resolved = 0`, ws.ID, c.Row).Scan(&open); err != nil {
			return v1.Conflict{}, nil, err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE v1_rows SET conflicted = ?, seq = ? WHERE workspace_id = ? AND id = ? AND conflicted <> ?`,
			b2i(open > 0), seq, ws.ID, c.Row, b2i(open > 0)); err != nil {
			return v1.Conflict{}, nil, err
		}
	}
	c.Resolved = true
	return c, res, tx.Commit()
}

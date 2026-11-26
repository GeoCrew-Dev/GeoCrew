package sqldb

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math"
	"strconv"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/merge"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

const rowCols = `layer_id, id, owner, fields, deleted, conflicted, seq`

func scanRow(r rowScanner) (v1.Row, error) {
	var row v1.Row
	var fields, deleted string
	var conflicted int
	err := r.Scan(&row.Layer, &row.ID, &row.Owner, &fields, &deleted, &conflicted, &row.Seq)
	if errors.Is(err, sql.ErrNoRows) {
		return row, store.ErrNotFound
	}
	if err != nil {
		return row, err
	}
	if err := json.Unmarshal([]byte(fields), &row.Fields); err != nil {
		return row, err
	}
	if deleted != "" {
		row.Deleted = &merge.Field{}
		if err := json.Unmarshal([]byte(deleted), row.Deleted); err != nil {
			return row, err
		}
	}
	row.Conflicted = conflicted != 0
	return row, nil
}

const conflictCols = `id, layer_id, row_id, seq, kind, field, kept, lost, resolved`

func scanConflict(r rowScanner) (v1.Conflict, error) {
	var c v1.Conflict
	var kept, lost string
	var resolved int
	if err := r.Scan(&c.ID, &c.Layer, &c.Row, &c.Seq, &c.Kind, &c.Field, &kept, &lost, &resolved); err != nil {
		return c, err
	}
	for _, x := range []struct {
		s string
		f **merge.Field
	}{{kept, &c.Kept}, {lost, &c.Lost}} {
		if x.s != "" {
			*x.f = &merge.Field{}
			if err := json.Unmarshal([]byte(x.s), *x.f); err != nil {
				return c, err
			}
		}
	}
	c.Resolved = resolved != 0
	return c, nil
}

func (v *V1) Row(ctx context.Context, workspaceID, id string) (v1.Row, []v1.Conflict, error) {
	row, err := scanRow(v.d.db.QueryRowContext(ctx, `SELECT `+rowCols+` FROM v1_rows WHERE workspace_id = ? AND id = ?`, workspaceID, id))
	if err != nil {
		return v1.Row{}, nil, err
	}
	rows, err := v.d.db.QueryContext(ctx, `SELECT `+conflictCols+` FROM v1_conflicts
		WHERE workspace_id = ? AND row_id = ? AND resolved = 0 ORDER BY seq, id`, workspaceID, id)
	if err != nil {
		return v1.Row{}, nil, err
	}
	defer rows.Close()
	out := []v1.Conflict{}
	for rows.Next() {
		c, err := scanConflict(rows)
		if err != nil {
			return v1.Row{}, nil, err
		}
		out = append(out, c)
	}
	return row, out, rows.Err()
}

func (v *V1) Push(ctx context.Context, workspaceID, actor string, clientNow time.Time, changes []v1.Change) ([]v1.Result, int64, error) {
	seen := map[string]bool{}
	for _, c := range changes {
		key := c.Layer + "/" + c.Row
		if c.Layer != v1.TableLayers {
			key = c.Row // a row id is unique across layers
		}
		if seen[key] {
			return nil, 0, store.ErrInvalid // one change per row and push (protocol 8.1)
		}
		seen[key] = true
	}
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return nil, 0, err
	}
	defer tx.Rollback()
	layers, err := v.layerMap(ctx, tx, ws.ID)
	if err != nil {
		return nil, 0, err
	}
	p := push{v: v, tx: tx, ws: ws, acc: acc, layers: layers, actor: actor, serverNow: v.clock()}
	p.clientNow = clientNow
	if clientNow.IsZero() {
		p.clientNow = p.serverNow
	}
	results := make([]v1.Result, 0, len(changes))
	for _, c := range changes {
		res, err := p.one(ctx, c)
		if err != nil {
			return nil, 0, err
		}
		results = append(results, res)
	}
	var last int64
	if err := tx.QueryRowContext(ctx, `SELECT last_seq FROM v1_workspaces WHERE id = ?`, ws.ID).Scan(&last); err != nil {
		return nil, 0, err
	}
	return results, last, tx.Commit()
}

func (v *V1) layerMap(ctx context.Context, q querier, workspaceID string) (map[string]v1.Layer, error) {
	rows, err := q.QueryContext(ctx, `SELECT `+layerCols+` FROM v1_layers WHERE workspace_id = ?`, workspaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]v1.Layer{}
	for rows.Next() {
		l, _, err := scanLayer(rows)
		if err != nil {
			return nil, err
		}
		out[l.ID] = l
	}
	return out, rows.Err()
}

// push carries one Push transaction.
type push struct {
	v                    *V1
	tx                   *Tx
	ws                   v1.Workspace
	acc                  perm.Workspace
	layers               map[string]v1.Layer
	actor                string
	clientNow, serverNow time.Time
}

func rejected(c v1.Change, code, message string) v1.Result {
	return v1.Result{ID: c.ID, Status: v1.Rejected, Error: code, Message: message}
}

func (p *push) one(ctx context.Context, c v1.Change) (v1.Result, error) {
	var seq int64
	err := p.tx.QueryRowContext(ctx, `SELECT seq FROM v1_changes WHERE workspace_id = ? AND id = ?`, p.ws.ID, c.ID).Scan(&seq)
	if err == nil {
		return v1.Result{ID: c.ID, Status: v1.Duplicate, Seq: seq}, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return v1.Result{}, err
	}
	switch {
	case !uuidPattern.MatchString(c.ID) || !uuidPattern.MatchString(c.Row):
		return rejected(c, v1.RejectInvalidValue, "change and row ids are lowercase UUIDs"), nil
	case p.ws.ArchivedAt != nil:
		return rejected(c, v1.RejectArchived, "the workspace is archived"), nil
	case len(c.Set) == 0 && c.Delete == nil:
		return rejected(c, v1.RejectInvalidValue, "the change changes nothing"), nil
	case c.Delete != nil && string(c.Delete.Value) != "true" && string(c.Delete.Value) != "false":
		return rejected(c, v1.RejectInvalidValue, "delete is true or false"), nil
	case c.Layer == v1.TableLayers:
		return p.layerProps(ctx, c)
	}
	l, ok := p.layers[c.Layer]
	if !ok || isDeleted(l) {
		return rejected(c, v1.RejectUnknownLayer, "no such layer"), nil
	}
	row, err := scanRow(p.tx.QueryRowContext(ctx, `SELECT `+rowCols+` FROM v1_rows WHERE workspace_id = ? AND id = ?`, p.ws.ID, c.Row))
	var cur *merge.Row
	owner := p.actor // a new row is its first submitter's
	switch {
	case err == nil && row.Layer != c.Layer:
		return rejected(c, v1.RejectInvalidValue, "the row belongs to another layer"), nil
	case err == nil:
		cur, owner = &merge.Row{Fields: row.Fields, Deleted: row.Deleted}, row.Owner
	case !errors.Is(err, store.ErrNotFound):
		return v1.Result{}, err
	}
	if (len(c.Set) > 0 || cur == nil) && !p.acc.CanTouchRow(p.actor, perm.EditRows, c.Layer, owner) {
		return rejected(c, v1.RejectForbidden, "edit_rows is required"), nil
	}
	if c.Delete != nil && cur != nil && !p.acc.CanTouchRow(p.actor, perm.DeleteRows, c.Layer, owner) {
		return rejected(c, v1.RejectForbidden, "delete_rows is required"), nil
	}
	live, types := map[string]bool{}, map[string]string{}
	for _, f := range l.Fields {
		live[f.ID], types[f.ID] = true, f.Type
	}
	if l.GeometryType != "None" {
		live["geom"] = true
	}
	for id, e := range c.Set {
		if !live[id] {
			continue // becomes a deleted_field conflict, so the value is kept
		}
		if msg := checkValue(l.GeometryType, types[id], id, e.Value); msg != "" {
			return rejected(c, v1.RejectInvalidValue, msg), nil
		}
	}
	if seq, err = nextSeq(ctx, p.tx, p.ws.ID); err != nil {
		return v1.Result{}, err
	}
	merged, cs := merge.Apply(cur, merge.Change{By: p.actor, BaseSeq: c.BaseSeq, ClientNow: p.clientNow, Set: c.Set, Delete: c.Delete}, live, seq, p.serverNow)
	recorded, err := p.recordConflicts(ctx, c.Layer, c.Row, seq, cs)
	if err != nil {
		return v1.Result{}, err
	}
	fields, err := json.Marshal(merged.Fields)
	if err != nil {
		return v1.Result{}, err
	}
	deleted := ""
	if merged.Deleted != nil {
		b, err := json.Marshal(merged.Deleted)
		if err != nil {
			return v1.Result{}, err
		}
		deleted = string(b)
	}
	conflicted := b2i(len(cs) > 0 || row.Conflicted)
	if cur == nil {
		_, err = p.tx.ExecContext(ctx, `INSERT INTO v1_rows (workspace_id, `+rowCols+`) VALUES (?,?,?,?,?,?,?,?)`,
			p.ws.ID, c.Layer, c.Row, owner, string(fields), deleted, conflicted, seq)
	} else {
		_, err = p.tx.ExecContext(ctx, `UPDATE v1_rows SET fields = ?, deleted = ?, conflicted = ?, seq = ? WHERE workspace_id = ? AND id = ?`,
			string(fields), deleted, conflicted, seq, p.ws.ID, c.Row)
	}
	if err != nil {
		return v1.Result{}, err
	}
	return v1.Result{ID: c.ID, Status: v1.Applied, Seq: seq, Conflicts: recorded}, p.recordChange(ctx, c, seq)
}

// layerProps merges a change to a layer's name, style, form or order
// (protocol 7.1): edit_schema, like changing its structure.
func (p *push) layerProps(ctx context.Context, c v1.Change) (v1.Result, error) {
	l, ok := p.layers[c.Row]
	if !ok || isDeleted(l) {
		return rejected(c, v1.RejectUnknownLayer, "no such layer"), nil
	}
	if c.Delete != nil {
		return rejected(c, v1.RejectInvalidValue, "layers are deleted on their own, not through a change"), nil
	}
	if !p.acc.Has(p.actor, perm.EditSchema) || p.acc.Level(p.actor, perm.ViewLayer, l.ID) == perm.None {
		return rejected(c, v1.RejectForbidden, "edit_schema is required"), nil
	}
	for id, e := range c.Set {
		if msg := checkLayerProp(id, e.Value); msg != "" {
			return rejected(c, v1.RejectInvalidValue, msg), nil
		}
	}
	seq, err := nextSeq(ctx, p.tx, p.ws.ID)
	if err != nil {
		return v1.Result{}, err
	}
	live := map[string]bool{v1.PropName: true, v1.PropStyle: true, v1.PropForm: true, v1.PropOrder: true}
	merged, cs := merge.Apply(&merge.Row{Fields: l.Props}, merge.Change{By: p.actor, BaseSeq: c.BaseSeq, ClientNow: p.clientNow, Set: c.Set}, live, seq, p.serverNow)
	recorded, err := p.recordConflicts(ctx, v1.TableLayers, l.ID, seq, cs)
	if err != nil {
		return v1.Result{}, err
	}
	props, err := json.Marshal(merged.Fields)
	if err != nil {
		return v1.Result{}, err
	}
	if _, err := p.tx.ExecContext(ctx, `UPDATE v1_layers SET props = ?, seq = ? WHERE workspace_id = ? AND id = ?`, string(props), seq, p.ws.ID, l.ID); err != nil {
		return v1.Result{}, err
	}
	l.Props, l.Seq = merged.Fields, seq
	p.layers[l.ID] = l
	return v1.Result{ID: c.ID, Status: v1.Applied, Seq: seq, Conflicts: recorded}, p.recordChange(ctx, c, seq)
}

func (p *push) recordConflicts(ctx context.Context, layer, row string, seq int64, cs []merge.Conflict) ([]v1.Conflict, error) {
	out := []v1.Conflict{}
	for _, mc := range cs {
		c := v1.Conflict{ID: randHex(8), Layer: layer, Row: row, Seq: seq, Conflict: mc}
		var kept, lost string
		for _, x := range []struct {
			f *merge.Field
			s *string
		}{{mc.Kept, &kept}, {mc.Lost, &lost}} {
			if x.f != nil {
				b, err := json.Marshal(x.f)
				if err != nil {
					return nil, err
				}
				*x.s = string(b)
			}
		}
		if _, err := p.tx.ExecContext(ctx, `INSERT INTO v1_conflicts (workspace_id, `+conflictCols+`, update_seq) VALUES (?,?,?,?,?,?,?,?,?,0,?)`,
			p.ws.ID, c.ID, layer, row, seq, mc.Kind, mc.Field, kept, lost, seq); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, nil
}

// recordChange keeps the change as it came, so a retry is recognised and the
// history holds every value ever sent, even of fields deleted since.
func (p *push) recordChange(ctx context.Context, c v1.Change, seq int64) error {
	body, err := json.Marshal(map[string]any{"layer": c.Layer, "row": c.Row, "base_seq": c.BaseSeq,
		"client_now": p.clientNow, "set": c.Set, "delete": c.Delete})
	if err != nil {
		return err
	}
	_, err = p.tx.ExecContext(ctx, `INSERT INTO v1_changes (workspace_id, id, row_id, seq, by_member, at, body) VALUES (?,?,?,?,?,?,?)`,
		p.ws.ID, c.ID, c.Row, seq, p.actor, fmtV1Time(p.serverNow), string(body))
	return err
}

// maxSafeInt is the largest integer every JSON parser keeps exactly (protocol 7.2).
const maxSafeInt = 1<<53 - 1

// checkValue tells why a field value does not fit its type, or "" when it
// does; null fits every type (protocol 7.2).
func checkValue(geometryType, fieldType, id string, raw json.RawMessage) string {
	if !json.Valid(raw) {
		return id + " is not JSON"
	}
	if string(raw) == "null" {
		return ""
	}
	if id == "geom" {
		if !validGeometry(geometryType, raw) {
			return "geom must be a " + geometryType + " geometry"
		}
		return ""
	}
	var s string
	var n json.Number
	switch fieldType {
	case "string":
		if json.Unmarshal(raw, &s) == nil {
			return ""
		}
	case "integer":
		if json.Unmarshal(raw, &n) == nil {
			if i, err := strconv.ParseInt(n.String(), 10, 64); err == nil && i >= -maxSafeInt && i <= maxSafeInt {
				return ""
			}
		}
	case "real":
		if json.Unmarshal(raw, &n) == nil {
			return ""
		}
	case "boolean":
		if string(raw) == "true" || string(raw) == "false" {
			return ""
		}
	case "date":
		if json.Unmarshal(raw, &s) == nil {
			if _, err := time.Parse(time.DateOnly, s); err == nil {
				return ""
			}
		}
	case "datetime":
		if json.Unmarshal(raw, &s) == nil {
			if _, err := time.Parse(time.RFC3339Nano, s); err == nil {
				return ""
			}
		}
	case "json":
		return ""
	}
	return id + " must be " + fieldType
}

// validGeometry checks a GeoJSON geometry of the layer's type: the nesting
// of its coordinates and finite positions of two or three numbers.
func validGeometry(geometryType string, raw json.RawMessage) bool {
	var g struct {
		Type        string          `json:"type"`
		Coordinates json.RawMessage `json:"coordinates"`
	}
	if json.Unmarshal(raw, &g) != nil || g.Type != geometryType {
		return false
	}
	pos := func(p []float64) bool {
		if len(p) < 2 || len(p) > 3 {
			return false
		}
		for _, x := range p {
			if math.IsNaN(x) || math.IsInf(x, 0) {
				return false
			}
		}
		return true
	}
	line := func(ps [][]float64, min int) bool {
		if len(ps) < min {
			return false
		}
		for _, p := range ps {
			if !pos(p) {
				return false
			}
		}
		return true
	}
	rings := func(rs [][][]float64, min int) bool {
		if len(rs) == 0 {
			return false
		}
		for _, r := range rs {
			if !line(r, min) {
				return false
			}
		}
		return true
	}
	switch g.Type {
	case "Point":
		var p []float64
		return json.Unmarshal(g.Coordinates, &p) == nil && pos(p)
	case "LineString", "MultiPoint":
		var ps [][]float64
		min := 2
		if g.Type == "MultiPoint" {
			min = 1
		}
		return json.Unmarshal(g.Coordinates, &ps) == nil && line(ps, min)
	case "Polygon", "MultiLineString":
		var rs [][][]float64
		min := 4
		if g.Type == "MultiLineString" {
			min = 2
		}
		return json.Unmarshal(g.Coordinates, &rs) == nil && rings(rs, min)
	case "MultiPolygon":
		var ps [][][][]float64
		if json.Unmarshal(g.Coordinates, &ps) != nil || len(ps) == 0 {
			return false
		}
		for _, rs := range ps {
			if !rings(rs, 4) {
				return false
			}
		}
		return true
	}
	return false
}

// checkLayerProp tells why a layer property value does not fit, or "".
func checkLayerProp(id string, raw json.RawMessage) string {
	if !json.Valid(raw) {
		return id + " is not JSON"
	}
	switch id {
	case v1.PropName:
		var s string
		if json.Unmarshal(raw, &s) == nil {
			if _, err := clean(s, 64); err == nil {
				return ""
			}
		}
		return "name must be a text of 1 to 64 characters"
	case v1.PropStyle, v1.PropForm:
		var o map[string]json.RawMessage
		if string(raw) == "null" || (len(raw) <= maxJSONProp && json.Unmarshal(raw, &o) == nil) {
			return ""
		}
		return id + " must be a JSON object"
	case v1.PropOrder:
		var n json.Number
		if string(raw) == "null" || json.Unmarshal(raw, &n) == nil {
			return ""
		}
		return "order must be a number"
	}
	return "layers have no property " + id
}

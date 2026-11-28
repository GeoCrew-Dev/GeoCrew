package sqldb

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math"
	"regexp"
	"strings"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/merge"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

var (
	// uuidPattern is how clients write the ids they make (protocol 3.1).
	uuidPattern  = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	fieldPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
	epsgPattern  = regexp.MustCompile(`^EPSG:[0-9]{1,9}$`)
)

// maxJSONProp bounds a layer's style or form.
const maxJSONProp = 64 << 10

// nextSeq takes the workspace's next sequence number, which rows and layers share.
func nextSeq(ctx context.Context, tx *Tx, workspaceID string) (int64, error) {
	var seq int64
	err := tx.QueryRowContext(ctx, `UPDATE v1_workspaces SET last_seq = last_seq + 1 WHERE id = ? RETURNING last_seq`, workspaceID).Scan(&seq)
	return seq, err
}

// validCRS accepts EPSG:<code> or WKT2 text, which GDAL reads where it is needed.
func validCRS(crs string) bool {
	if strings.HasPrefix(crs, "EPSG:") {
		return epsgPattern.MatchString(crs)
	}
	return strings.TrimSpace(crs) != "" && len(crs) <= 16<<10
}

func validField(f v1.FieldDef) bool {
	if !fieldPattern.MatchString(f.ID) || f.ID == "geom" || !v1.FieldTypes[f.Type] {
		return false
	}
	if _, err := clean(f.Name, 64); err != nil {
		return false
	}
	return validOptions(f.Options)
}

// sameName is how field names compare (protocol 7.2): trimmed, ignoring case.
func sameName(a, b string) bool { return strings.EqualFold(strings.TrimSpace(a), strings.TrimSpace(b)) }

// sameField reports whether a and b are one definition: the same id, name, type and options.
func sameField(a, b v1.FieldDef) bool {
	return a.ID == b.ID && a.Name == b.Name && a.Type == b.Type && bytes.Equal(compactJSON(a.Options), compactJSON(b.Options))
}

func validOptions(o json.RawMessage) bool {
	if o == nil {
		return true
	}
	var list []json.RawMessage
	return len(o) <= maxJSONProp && json.Unmarshal(o, &list) == nil
}

func validJSONProp(b json.RawMessage) bool {
	return b == nil || (len(b) <= maxJSONProp && json.Valid(b))
}

const layerCols = `id, geometry_type, crs, fields, retired, schema_version, props, deleted, created_by, seq`

// scanLayer reads a layer and the ids of its deleted fields, which are never used again.
func scanLayer(r rowScanner) (v1.Layer, []string, error) {
	var l v1.Layer
	var fields, retired, props, deleted string
	err := r.Scan(&l.ID, &l.GeometryType, &l.CRS, &fields, &retired, &l.SchemaVersion, &props, &deleted, &l.CreatedBy, &l.Seq)
	if errors.Is(err, sql.ErrNoRows) {
		return l, nil, store.ErrNotFound
	}
	if err != nil {
		return l, nil, err
	}
	var gone []string
	for _, x := range []struct {
		s string
		v any
	}{{fields, &l.Fields}, {retired, &gone}, {props, &l.Props}} {
		if err := json.Unmarshal([]byte(x.s), x.v); err != nil {
			return l, nil, err
		}
	}
	if deleted != "" {
		l.Deleted = &merge.Field{}
		if err := json.Unmarshal([]byte(deleted), l.Deleted); err != nil {
			return l, nil, err
		}
	}
	return l, gone, nil
}

func isDeleted(l v1.Layer) bool { return l.Deleted != nil && string(l.Deleted.Value) == "true" }

func (v *V1) Layers(ctx context.Context, workspaceID string) ([]v1.Layer, error) {
	if _, err := v.Workspace(ctx, workspaceID); err != nil {
		return nil, err
	}
	rows, err := v.d.db.QueryContext(ctx, `SELECT `+layerCols+` FROM v1_layers WHERE workspace_id = ? ORDER BY seq`, workspaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []v1.Layer{}
	for rows.Next() {
		l, _, err := scanLayer(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

func (v *V1) Layer(ctx context.Context, workspaceID, id string) (v1.Layer, error) {
	l, _, err := v.layer(ctx, v.d.db, workspaceID, id)
	return l, err
}

func (v *V1) layer(ctx context.Context, q querier, workspaceID, id string) (v1.Layer, []string, error) {
	return scanLayer(q.QueryRowContext(ctx, `SELECT `+layerCols+` FROM v1_layers WHERE workspace_id = ? AND id = ?`, workspaceID, id))
}

// sameSchema compares what CreateLayer fixes: geometry type, CRS and fields.
func sameSchema(l v1.Layer, in v1.LayerInput) bool {
	a, err1 := json.Marshal(l.Fields)
	b, err2 := json.Marshal(in.Fields)
	return err1 == nil && err2 == nil && l.GeometryType == in.GeometryType && l.CRS == in.CRS && bytes.Equal(compactJSON(a), compactJSON(b))
}

func compactJSON(b []byte) []byte {
	var out bytes.Buffer
	if json.Compact(&out, b) != nil {
		return b
	}
	return out.Bytes()
}

func (v *V1) CreateLayer(ctx context.Context, workspaceID, actor string, in v1.LayerInput) (v1.Layer, error) {
	name, err := clean(in.Name, 64)
	if err != nil || !uuidPattern.MatchString(in.ID) || !v1.GeometryTypes[in.GeometryType] || !validCRS(in.CRS) ||
		!validJSONProp(in.Style) || !validJSONProp(in.Form) || (in.Order != nil && (math.IsNaN(*in.Order) || math.IsInf(*in.Order, 0))) {
		return v1.Layer{}, store.ErrInvalid
	}
	in.Fields = append([]v1.FieldDef(nil), in.Fields...) // normalised below; the caller's slice stays as it was
	seen := map[string]bool{}
	for i, f := range in.Fields {
		if !validField(f) || seen[f.ID] {
			return v1.Layer{}, store.ErrInvalid
		}
		for _, other := range in.Fields[:i] {
			if sameName(other.Name, f.Name) {
				return v1.Layer{}, store.ErrInvalid // a name is one field's
			}
		}
		seen[f.ID] = true
		in.Fields[i].Name = strings.TrimSpace(f.Name)
		if f.Options != nil {
			in.Fields[i].Options = compactJSON(f.Options)
		}
	}
	if in.Fields == nil {
		in.Fields = []v1.FieldDef{}
	}
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return v1.Layer{}, err
	}
	defer tx.Rollback()
	if ws.ArchivedAt != nil {
		return v1.Layer{}, store.ErrArchived
	}
	if !acc.Has(actor, perm.ManageLayers) {
		return v1.Layer{}, store.ErrForbidden
	}
	cur, _, err := v.layer(ctx, tx, ws.ID, in.ID)
	switch {
	case err == nil && sameSchema(cur, in):
		return cur, nil // a retry
	case err == nil:
		return cur, store.ErrStale
	case !errors.Is(err, store.ErrNotFound):
		return v1.Layer{}, err
	}
	seq, err := nextSeq(ctx, tx, ws.ID)
	if err != nil {
		return v1.Layer{}, err
	}
	at := v.clock()
	prop := func(value json.RawMessage) merge.Field { return merge.Field{Value: value, By: actor, At: at, Seq: seq} }
	nameJSON, _ := json.Marshal(name)
	l := v1.Layer{ID: in.ID, GeometryType: in.GeometryType, CRS: in.CRS, Fields: in.Fields, SchemaVersion: 1,
		Props: map[string]merge.Field{v1.PropName: prop(nameJSON)}, CreatedBy: actor, Seq: seq}
	if in.Style != nil {
		l.Props[v1.PropStyle] = prop(compactJSON(in.Style))
	}
	if in.Form != nil {
		l.Props[v1.PropForm] = prop(compactJSON(in.Form))
	}
	if in.Order != nil {
		order, _ := json.Marshal(*in.Order)
		l.Props[v1.PropOrder] = prop(order)
	}
	if err := v.writeLayer(ctx, tx, ws.ID, l, nil, true); err != nil {
		return v1.Layer{}, err
	}
	return l, tx.Commit()
}

// writeLayer inserts (insert) or updates a layer with the ids of its deleted fields.
func (v *V1) writeLayer(ctx context.Context, tx *Tx, workspaceID string, l v1.Layer, retired []string, insert bool) error {
	if retired == nil {
		retired = []string{}
	}
	fields, err := json.Marshal(l.Fields)
	if err != nil {
		return err
	}
	gone, err := json.Marshal(retired)
	if err != nil {
		return err
	}
	props, err := json.Marshal(l.Props)
	if err != nil {
		return err
	}
	deleted := ""
	if l.Deleted != nil {
		b, err := json.Marshal(l.Deleted)
		if err != nil {
			return err
		}
		deleted = string(b)
	}
	if insert {
		_, err = tx.ExecContext(ctx, `INSERT INTO v1_layers (workspace_id, `+layerCols+`) VALUES (?,?,?,?,?,?,?,?,?,?,?)`,
			workspaceID, l.ID, l.GeometryType, l.CRS, string(fields), string(gone), l.SchemaVersion, string(props), deleted, l.CreatedBy, l.Seq)
		return err
	}
	_, err = tx.ExecContext(ctx, `UPDATE v1_layers SET fields = ?, retired = ?, schema_version = ?, props = ?, deleted = ?, seq = ?
		WHERE workspace_id = ? AND id = ?`, string(fields), string(gone), l.SchemaVersion, string(props), deleted, l.Seq, workspaceID, l.ID)
	return err
}

func (v *V1) DeleteLayer(ctx context.Context, workspaceID, actor, id string) (v1.Layer, error) {
	return v.setLayerDeleted(ctx, workspaceID, actor, id, true)
}

func (v *V1) RestoreLayer(ctx context.Context, workspaceID, actor, id string) (v1.Layer, error) {
	return v.setLayerDeleted(ctx, workspaceID, actor, id, false)
}

func (v *V1) setLayerDeleted(ctx context.Context, workspaceID, actor, id string, deleted bool) (v1.Layer, error) {
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return v1.Layer{}, err
	}
	defer tx.Rollback()
	if ws.ArchivedAt != nil {
		return v1.Layer{}, store.ErrArchived
	}
	if !acc.Has(actor, perm.ManageLayers) {
		return v1.Layer{}, store.ErrForbidden
	}
	l, retired, err := v.layer(ctx, tx, ws.ID, id)
	if err != nil {
		return v1.Layer{}, err
	}
	if isDeleted(l) == deleted {
		return l, nil // already so
	}
	seq, err := nextSeq(ctx, tx, ws.ID)
	if err != nil {
		return v1.Layer{}, err
	}
	value := json.RawMessage("false")
	if deleted {
		value = json.RawMessage("true")
	}
	l.Deleted = &merge.Field{Value: value, By: actor, At: v.clock(), Seq: seq}
	l.Seq = seq
	if err := v.writeLayer(ctx, tx, ws.ID, l, retired, false); err != nil {
		return v1.Layer{}, err
	}
	return l, tx.Commit()
}

func (v *V1) ChangeSchema(ctx context.Context, workspaceID, actor, id string, baseVersion int64, ops []v1.SchemaOp) (v1.Layer, error) {
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return v1.Layer{}, err
	}
	defer tx.Rollback()
	if ws.ArchivedAt != nil {
		return v1.Layer{}, store.ErrArchived
	}
	l, retired, err := v.layer(ctx, tx, ws.ID, id)
	if err == nil && isDeleted(l) {
		err = store.ErrNotFound
	}
	if err != nil {
		return v1.Layer{}, err
	}
	if !acc.Has(actor, perm.EditSchema) || acc.Level(actor, perm.ViewLayer, id) == perm.None {
		return v1.Layer{}, store.ErrForbidden
	}
	if len(ops) == 0 {
		return v1.Layer{}, store.ErrInvalid
	}
	// Adding fields cannot clash with what others changed meanwhile (an id is the adder's own): a request of adds only
	// goes onto the structure as it is now, so a phone can add a field offline and send it later (protocol 7.3).
	addsOnly := true
	for _, op := range ops {
		addsOnly = addsOnly && op.Op == "add_field"
	}
	if !addsOnly && baseVersion != l.SchemaVersion {
		return l, store.ErrStale
	}
	fields := append([]v1.FieldDef{}, l.Fields...)
	used := map[string]bool{}
	for _, f := range fields {
		used[f.ID] = true
	}
	for _, r := range retired {
		used[r] = true
	}
	find := func(fid string) int {
		for i, f := range fields {
			if f.ID == fid {
				return i
			}
		}
		return -1
	}
	// The field other than [except] that has [name], if any: a name is one field's in a layer.
	named := func(name string, except int) int {
		for i, f := range fields {
			if i != except && sameName(f.Name, name) {
				return i
			}
		}
		return -1
	}
	changed := false
	var renamed []string // checked once all ops are done, so two fields can swap names
	for _, op := range ops {
		i := find(op.ID)
		switch {
		case op.Op == "add_field" && op.Field != nil && validField(*op.Field):
			f := *op.Field
			f.Name = strings.TrimSpace(f.Name)
			if f.Options != nil {
				f.Options = compactJSON(f.Options)
			}
			if at := find(f.ID); at >= 0 && sameField(fields[at], f) {
				continue // sent again: it is there already
			}
			if used[f.ID] {
				return l, v1.ErrIDTaken
			}
			if at := named(f.Name, -1); at >= 0 {
				return l, &v1.NameTakenError{Field: fields[at]}
			}
			fields = append(fields, f)
			used[f.ID] = true
			changed = true
		case op.Op == "rename_field" && i >= 0:
			name, err := clean(op.Name, 64)
			if err != nil {
				return v1.Layer{}, err
			}
			if name != fields[i].Name {
				fields[i].Name = name
				renamed = append(renamed, op.ID)
				changed = true
			}
		case op.Op == "set_options" && i >= 0 && validOptions(op.Options):
			fields[i].Options = nil
			if op.Options != nil {
				fields[i].Options = compactJSON(op.Options)
			}
			changed = true
		case op.Op == "delete_field" && i >= 0:
			fields = append(fields[:i], fields[i+1:]...)
			retired = append(retired, op.ID)
			changed = true
		default:
			return v1.Layer{}, store.ErrInvalid
		}
	}
	for _, fid := range renamed {
		if i := find(fid); i >= 0 {
			if at := named(fields[i].Name, i); at >= 0 {
				return l, &v1.NameTakenError{Field: fields[at]}
			}
		}
	}
	if !changed {
		return l, nil // every add sent again, or names as they were: the structure stays as it is, its version too
	}
	seq, err := nextSeq(ctx, tx, ws.ID)
	if err != nil {
		return v1.Layer{}, err
	}
	l.Fields, l.SchemaVersion, l.Seq = fields, l.SchemaVersion+1, seq
	if err := v.writeLayer(ctx, tx, ws.ID, l, retired, false); err != nil {
		return v1.Layer{}, err
	}
	return l, tx.Commit()
}

func (v *V1) LayerOverrides(ctx context.Context, workspaceID, layer string) (map[string]map[string]perm.Level, error) {
	if _, _, err := v.layer(ctx, v.d.db, workspaceID, layer); err != nil {
		return nil, err
	}
	all, err := v.overrides(ctx, v.d.db, workspaceID)
	if err != nil {
		return nil, err
	}
	if all[layer] == nil {
		return map[string]map[string]perm.Level{}, nil
	}
	return all[layer], nil
}

// overrides loads every layer override: layer -> role -> permission -> level.
func (v *V1) overrides(ctx context.Context, q querier, workspaceID string) (map[string]map[string]map[string]perm.Level, error) {
	rows, err := q.QueryContext(ctx, `SELECT layer_id, role_id, permissions FROM v1_layer_overrides WHERE workspace_id = ?`, workspaceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]map[string]map[string]perm.Level{}
	for rows.Next() {
		var layer, role, perms string
		if err := rows.Scan(&layer, &role, &perms); err != nil {
			return nil, err
		}
		set := map[string]perm.Level{}
		if err := json.Unmarshal([]byte(perms), &set); err != nil {
			return nil, err
		}
		if out[layer] == nil {
			out[layer] = map[string]map[string]perm.Level{}
		}
		out[layer][role] = set
	}
	return out, rows.Err()
}

func (v *V1) SetLayerOverride(ctx context.Context, workspaceID, actor, layer, role string, set map[string]perm.Level) error {
	for p, l := range set {
		if !perm.Overridable[p] || l < perm.None || l > perm.All || (l == perm.Own && !perm.Scoped(p)) {
			return store.ErrInvalid
		}
	}
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, _, err := v.layer(ctx, tx, ws.ID, layer); err != nil {
		return err
	}
	if _, ok := acc.Roles[role]; !ok {
		return store.ErrNotFound
	}
	if !acc.CanSetOverride(actor, role, set) {
		return store.ErrForbidden
	}
	b, err := perm.MarshalPermissions(set)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM v1_layer_overrides WHERE workspace_id = ? AND layer_id = ? AND role_id = ?`, ws.ID, layer, role); err != nil {
		return err
	}
	after := copyAccess(acc)
	if after.Layers[layer] == nil {
		after.Layers[layer] = map[string]map[string]perm.Level{}
	}
	delete(after.Layers[layer], role)
	if len(set) > 0 {
		if _, err := tx.ExecContext(ctx, `INSERT INTO v1_layer_overrides (workspace_id, layer_id, role_id, permissions) VALUES (?,?,?,?)`,
			ws.ID, layer, role, string(b)); err != nil {
			return err
		}
		after.Layers[layer][role] = copyPermissions(set)
	}
	if err := v.bumpChanged(ctx, tx, ws.ID, acc, after); err != nil {
		return err
	}
	if err := v.audit(ctx, tx, ws.ID, actor, v1.AuditSetOverride, layer, map[string]any{"role": role, "set": json.RawMessage(b)}, v.clock()); err != nil {
		return err
	}
	return tx.Commit()
}

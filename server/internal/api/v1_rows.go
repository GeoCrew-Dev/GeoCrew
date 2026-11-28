package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/merge"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

// Protocol v1 layers (5.6, 7.3), push (8.1), pull (8.2) and conflicts
// (8.3). The store merges and checks; this maps JSON.

func (s *Server) v1RowRoutes(mux *http.ServeMux) {
	ws := "/api/v1/workspaces/{ws}"
	mux.Handle("POST "+ws+"/layers", s.v1Auth(s.v1CreateLayer))
	mux.Handle("DELETE "+ws+"/layers/{layer}", s.v1Auth(s.v1DeleteLayer))
	mux.Handle("POST "+ws+"/layers/{layer}/restore", s.v1Auth(s.v1RestoreLayer))
	mux.Handle("POST "+ws+"/layers/{layer}/schema", s.v1Auth(s.v1ChangeSchema))
	mux.Handle("POST "+ws+"/rows", s.v1Auth(s.v1Push))
	mux.Handle("GET "+ws+"/changes", s.v1Auth(s.v1Pull))
	mux.Handle("POST "+ws+"/conflicts/{conflict}/resolve", s.v1Auth(s.v1Resolve))
}

// v1Layer is a layer as protocol 8.2 shows it: the schema, and each merged
// property (name, style, form, order) with who set it, when and at which seq.
func v1Layer(l v1.Layer) map[string]any {
	fields := l.Fields
	if fields == nil {
		fields = []v1.FieldDef{}
	}
	out := map[string]any{
		"id": l.ID, "geometry_type": l.GeometryType, "crs": l.CRS, "fields": fields,
		"schema_version": l.SchemaVersion, "created_by": l.CreatedBy, "seq": l.Seq, "deleted": l.Deleted,
	}
	for k, f := range l.Props {
		out[k] = f
	}
	return out
}

type v1RowJSON struct {
	Layer      string                 `json:"layer"`
	ID         string                 `json:"id"`
	Owner      string                 `json:"owner"`
	Conflicted bool                   `json:"conflicted"`
	Fields     map[string]merge.Field `json:"fields"`
	Deleted    *merge.Field           `json:"deleted"`
	Seq        int64                  `json:"seq"`
}

type v1ConflictJSON struct {
	ID       string       `json:"id"`
	Layer    string       `json:"layer"`
	Row      string       `json:"row"`
	Seq      int64        `json:"seq"`
	Resolved bool         `json:"resolved"`
	Kind     string       `json:"kind"`
	Field    string       `json:"field,omitempty"`
	Kept     *merge.Field `json:"kept,omitempty"`
	Lost     *merge.Field `json:"lost,omitempty"`
}

func v1Conflict(c v1.Conflict) v1ConflictJSON {
	return v1ConflictJSON{c.ID, c.Layer, c.Row, c.Seq, c.Resolved, c.Kind, c.Field, c.Kept, c.Lost}
}

func v1Conflicts(cs []v1.Conflict) []v1ConflictJSON {
	out := make([]v1ConflictJSON, 0, len(cs))
	for _, c := range cs {
		out = append(out, v1Conflict(c))
	}
	return out
}

type v1ResultJSON struct {
	ID        string            `json:"id"`
	Status    string            `json:"status"`
	Seq       int64             `json:"seq,omitempty"`
	Conflicts *[]v1ConflictJSON `json:"conflicts,omitempty"`
	Error     string            `json:"error,omitempty"`
	Message   string            `json:"message,omitempty"`
}

func v1Result(r v1.Result) v1ResultJSON {
	out := v1ResultJSON{ID: r.ID, Status: r.Status, Seq: r.Seq, Error: r.Error, Message: r.Message}
	if r.Status == v1.Applied {
		cs := v1Conflicts(r.Conflicts)
		out.Conflicts = &cs
	}
	return out
}

// v1Stale answers 409 stale with the layer as it now is (protocol 5.6, 7.3).
func (s *Server) v1Stale(w http.ResponseWriter, r *http.Request, l v1.Layer, err error, permission string) {
	if errors.Is(err, store.ErrStale) {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "stale", "message": "the layer has changed; here it is as it is now", "layer": v1Layer(l)})
		return
	}
	s.v1Refused(w, r, err, permission)
}

func (s *Server) v1CreateLayer(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ID           string          `json:"id"`
		Name         string          `json:"name"`
		GeometryType string          `json:"geometry_type"`
		CRS          string          `json:"crs"`
		Fields       []v1.FieldDef   `json:"fields"`
		Style        json.RawMessage `json:"style"`
		Form         json.RawMessage `json:"form"`
		Order        *float64        `json:"order"`
	}
	if !decode(w, r, &req) {
		return
	}
	l, err := s.V1.CreateLayer(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, v1.LayerInput{
		ID: req.ID, Name: req.Name, GeometryType: req.GeometryType, CRS: req.CRS, Fields: req.Fields,
		Style: req.Style, Form: req.Form, Order: req.Order,
	})
	if err != nil {
		s.v1Stale(w, r, l, err, perm.ManageLayers)
		return
	}
	writeJSON(w, http.StatusOK, v1Layer(l))
}

func (s *Server) v1DeleteLayer(w http.ResponseWriter, r *http.Request) {
	l, err := s.V1.DeleteLayer(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, r.PathValue("layer"))
	if err != nil {
		s.v1Refused(w, r, err, perm.ManageLayers)
		return
	}
	writeJSON(w, http.StatusOK, v1Layer(l))
}

func (s *Server) v1RestoreLayer(w http.ResponseWriter, r *http.Request) {
	l, err := s.V1.RestoreLayer(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, r.PathValue("layer"))
	if err != nil {
		s.v1Refused(w, r, err, perm.ManageLayers)
		return
	}
	writeJSON(w, http.StatusOK, v1Layer(l))
}

func (s *Server) v1ChangeSchema(w http.ResponseWriter, r *http.Request) {
	var req struct {
		BaseVersion *int64 `json:"base_version"`
		Ops         []struct {
			Op      string          `json:"op"`
			Field   *v1.FieldDef    `json:"field"`
			ID      string          `json:"id"`
			Name    string          `json:"name"`
			Options json.RawMessage `json:"options"`
		} `json:"ops"`
	}
	if !decode(w, r, &req) {
		return
	}
	if req.BaseVersion == nil || len(req.Ops) == 0 {
		writeErr(w, http.StatusBadRequest, "invalid", "base_version and at least one op are required")
		return
	}
	ops := make([]v1.SchemaOp, len(req.Ops))
	for i, o := range req.Ops {
		ops[i] = v1.SchemaOp{Op: o.Op, Field: o.Field, ID: o.ID, Name: o.Name, Options: o.Options}
	}
	l, err := s.V1.ChangeSchema(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, r.PathValue("layer"), *req.BaseVersion, ops)
	var taken *v1.NameTakenError
	switch {
	case errors.As(err, &taken):
		writeJSON(w, http.StatusConflict, map[string]any{"error": "name_taken", "message": "another field of the layer has that name", "field": taken.Field})
		return
	case errors.Is(err, v1.ErrIDTaken):
		writeErr(w, http.StatusConflict, "id_taken", "the layer has, or had, a field of that id defined otherwise")
		return
	case err != nil:
		s.v1Stale(w, r, l, err, perm.EditSchema)
		return
	}
	writeJSON(w, http.StatusOK, v1Layer(l))
}

func (s *Server) v1Push(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ClientNow *time.Time `json:"client_now"`
		Changes   []struct {
			ID      string                `json:"id"`
			Layer   string                `json:"layer"`
			Row     string                `json:"row"`
			BaseSeq int64                 `json:"base_seq"`
			Set     map[string]merge.Edit `json:"set"`
			Delete  *merge.Edit           `json:"delete"`
		} `json:"changes"`
	}
	if !decode(w, r, &req) {
		return
	}
	if req.ClientNow == nil {
		writeErr(w, http.StatusBadRequest, "invalid", "client_now is required")
		return
	}
	if len(req.Changes) > s.MaxPushFeatures {
		writeErr(w, http.StatusRequestEntityTooLarge, "batch_too_large", "at most max_push_batch changes per push")
		return
	}
	changes := make([]v1.Change, len(req.Changes))
	for i, c := range req.Changes {
		// A missing edit time would read as year 1 and lose every merge.
		timed := c.Delete == nil || !c.Delete.EditedAt.IsZero()
		for _, e := range c.Set {
			timed = timed && !e.EditedAt.IsZero()
		}
		if !timed {
			writeErr(w, http.StatusBadRequest, "invalid", "every edit needs edited_at")
			return
		}
		changes[i] = v1.Change{ID: c.ID, Layer: c.Layer, Row: c.Row, BaseSeq: c.BaseSeq, Set: c.Set, Delete: c.Delete}
	}
	results, seq, err := s.V1.Push(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, *req.ClientNow, changes)
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	out := make([]v1ResultJSON, len(results))
	for i, res := range results {
		out[i] = v1Result(res)
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": out, "seq": seq})
}

func (s *Server) v1Pull(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	since, limit := int64(0), v1.MaxPull
	var err error
	if v := q.Get("since"); v != "" {
		if since, err = strconv.ParseInt(v, 10, 64); err != nil || since < 0 {
			writeErr(w, http.StatusBadRequest, "invalid", "since is a seq, 0 or more")
			return
		}
	}
	if v := q.Get("limit"); v != "" {
		if limit, err = strconv.Atoi(v); err != nil || limit < 1 {
			writeErr(w, http.StatusBadRequest, "invalid", "limit is 1 or more")
			return
		}
	}
	var only []string
	if v := q.Get("layers"); v != "" {
		for _, id := range strings.Split(v, ",") {
			if id != "" {
				only = append(only, id)
			}
		}
	}
	p, err := s.V1.Pull(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, since, limit, only)
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	layers := make([]map[string]any, 0, len(p.Layers))
	for _, l := range p.Layers {
		layers = append(layers, v1Layer(l))
	}
	rows := make([]v1RowJSON, 0, len(p.Rows))
	for _, row := range p.Rows {
		fields := row.Fields
		if fields == nil {
			fields = map[string]merge.Field{}
		}
		rows = append(rows, v1RowJSON{row.Layer, row.ID, row.Owner, row.Conflicted, fields, row.Deleted, row.Seq})
	}
	writeJSON(w, http.StatusOK, map[string]any{"layers": layers, "rows": rows, "conflicts": v1Conflicts(p.Conflicts), "seq": p.Seq, "more": p.More})
}

func (s *Server) v1Resolve(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Choose string `json:"choose"`
	}
	if !decode(w, r, &req) {
		return
	}
	if req.Choose != v1.ChooseKept && req.Choose != v1.ChooseLost {
		writeErr(w, http.StatusBadRequest, "invalid", "choose is kept or lost")
		return
	}
	c, res, err := s.V1.ResolveConflict(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, r.PathValue("conflict"), req.Choose)
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	out := map[string]any{"conflict": v1Conflict(c)}
	if res != nil {
		out["result"] = v1Result(*res)
	}
	writeJSON(w, http.StatusOK, out)
}

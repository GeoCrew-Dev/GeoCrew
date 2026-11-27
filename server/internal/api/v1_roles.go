package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

// Protocol v1 roles (5.4), layer overrides (5.5) and the audit log (5.7).

func (s *Server) v1RoleRoutes(mux *http.ServeMux) {
	ws := "/api/v1/workspaces/{ws}"
	mux.Handle("GET "+ws+"/roles", s.v1Auth(s.v1Roles))
	mux.Handle("POST "+ws+"/roles", s.v1Auth(s.v1CreateRole))
	mux.Handle("PATCH "+ws+"/roles/{role}", s.v1Auth(s.v1UpdateRole))
	mux.Handle("DELETE "+ws+"/roles/{role}", s.v1Auth(s.v1DeleteRole))
	mux.Handle("GET "+ws+"/layers/{layer}/overrides", s.v1Auth(s.v1Overrides))
	mux.Handle("PUT "+ws+"/layers/{layer}/overrides/{role}", s.v1Auth(s.v1SetOverride))
	mux.Handle("GET "+ws+"/audit", s.v1Auth(s.v1Audit))
}

type v1RoleJSON struct {
	ID          string          `json:"id"`
	Name        string          `json:"name"`
	Position    int             `json:"position"`
	Permissions json.RawMessage `json:"permissions"`
	Version     int64           `json:"version"`
}

func v1Role(r v1.Role) (v1RoleJSON, error) {
	ps, err := perm.MarshalPermissions(r.Permissions)
	return v1RoleJSON{r.ID, r.Name, r.Position, ps, r.Version}, err
}

func (s *Server) v1Roles(w http.ResponseWriter, r *http.Request) {
	roles, err := s.V1.Roles(r.Context(), r.PathValue("ws"))
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	out := make([]v1RoleJSON, len(roles))
	for i, role := range roles {
		if out[i], err = v1Role(role); err != nil {
			s.v1Fail(w, r, err)
			return
		}
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) v1WriteRole(w http.ResponseWriter, r *http.Request, status int, role v1.Role) {
	out, err := v1Role(role)
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	writeJSON(w, status, out)
}

func (s *Server) v1CreateRole(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name        string          `json:"name"`
		Position    *int            `json:"position"`
		Permissions json.RawMessage `json:"permissions"`
	}
	if !decode(w, r, &req) {
		return
	}
	ps, ok := v1Levels(w, req.Permissions)
	if !ok {
		return
	}
	if req.Position == nil {
		writeErr(w, http.StatusBadRequest, "invalid", "position is required")
		return
	}
	role, err := s.V1.CreateRole(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, req.Name, *req.Position, ps)
	if err != nil {
		s.v1Refused(w, r, err, perm.ManageRoles)
		return
	}
	s.v1WriteRole(w, r, http.StatusCreated, role)
}

// v1UpdateRole changes a role from the version the caller saw; a role changed
// since answers 409 stale with the role as it is now (protocol 5.4).
func (s *Server) v1UpdateRole(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name        *string         `json:"name"`
		Position    *int            `json:"position"`
		Permissions json.RawMessage `json:"permissions"`
		BaseVersion *int64          `json:"base_version"`
	}
	if !decode(w, r, &req) {
		return
	}
	ps, ok := v1Levels(w, req.Permissions)
	if !ok {
		return
	}
	if req.BaseVersion == nil {
		writeErr(w, http.StatusBadRequest, "invalid", "base_version is required")
		return
	}
	role, err := s.V1.UpdateRole(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, r.PathValue("role"),
		v1.RoleUpdate{Name: req.Name, Position: req.Position, Permissions: ps}, *req.BaseVersion)
	if errors.Is(err, store.ErrStale) {
		current, merr := v1Role(role)
		if merr != nil {
			s.v1Fail(w, r, merr)
			return
		}
		writeJSON(w, http.StatusConflict, map[string]any{"error": "stale", "message": "the role has changed; here it is as it is now", "role": current})
		return
	}
	if err != nil {
		s.v1Refused(w, r, err, perm.ManageRoles)
		return
	}
	s.v1WriteRole(w, r, http.StatusOK, role)
}

func (s *Server) v1DeleteRole(w http.ResponseWriter, r *http.Request) {
	if err := s.V1.DeleteRole(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, r.PathValue("role")); err != nil {
		s.v1Refused(w, r, err, perm.ManageRoles)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// v1Levels reads permissions as the protocol writes them (protocol 4.2): an
// object of levels, or nothing (nil). A level it does not know is 400 invalid.
func v1Levels(w http.ResponseWriter, raw json.RawMessage) (map[string]perm.Level, bool) {
	if len(raw) == 0 || string(raw) == "null" {
		return nil, true
	}
	var levels map[string]json.RawMessage
	if err := json.Unmarshal(raw, &levels); err != nil {
		writeErr(w, http.StatusBadRequest, "invalid", "permissions are an object of levels")
		return nil, false
	}
	out := make(map[string]perm.Level, len(levels))
	for name, value := range levels {
		var l perm.Level
		if err := l.UnmarshalJSON(value); err != nil {
			writeErr(w, http.StatusBadRequest, "invalid", name+": "+err.Error())
			return nil, false
		}
		out[name] = l
	}
	return out, true
}

// v1Overrides lists a layer's overrides to those who can see the layer (protocol 5.5).
func (s *Server) v1Overrides(w http.ResponseWriter, r *http.Request) {
	ws, layer := r.PathValue("ws"), r.PathValue("layer")
	l, err := s.V1.Layer(r.Context(), ws, layer)
	if err == nil && l.Deleted != nil && string(l.Deleted.Value) == "true" {
		err = store.ErrNotFound // a deleted layer is hidden with its rows (protocol 5.6)
	}
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	access, err := s.V1.Access(r.Context(), ws)
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	if access.Level(v1Who(r).Member.ID, perm.ViewLayer, layer) == perm.None {
		s.v1Fail(w, r, store.ErrNotFound) // a layer one cannot see is not there
		return
	}
	overrides, err := s.V1.LayerOverrides(r.Context(), ws, layer)
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	out := make(map[string]json.RawMessage, len(overrides))
	for role, set := range overrides {
		if out[role], err = perm.MarshalPermissions(set); err != nil {
			s.v1Fail(w, r, err)
			return
		}
	}
	writeJSON(w, http.StatusOK, out)
}

// v1SetOverride replaces a role's overrides on a layer; an empty set removes them.
func (s *Server) v1SetOverride(w http.ResponseWriter, r *http.Request) {
	var raw json.RawMessage
	if !decode(w, r, &raw) {
		return
	}
	set, ok := v1Levels(w, raw)
	if !ok {
		return
	}
	if set == nil {
		set = map[string]perm.Level{}
	}
	if err := s.V1.SetLayerOverride(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, r.PathValue("layer"), r.PathValue("role"), set); err != nil {
		s.v1Refused(w, r, err, perm.ManageRoles)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

type v1AuditJSON struct {
	ID     int64           `json:"id"`
	At     time.Time       `json:"at"`
	By     string          `json:"by"`
	Action string          `json:"action"`
	Target string          `json:"target,omitempty"`
	Detail json.RawMessage `json:"detail,omitempty"`
}

// v1Audit reads the audit log newest first, below entry before; only the leader may (protocol 5.7).
func (s *Server) v1Audit(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	before, limit := int64(0), 100
	var err error
	if v := q.Get("before"); v != "" {
		if before, err = strconv.ParseInt(v, 10, 64); err != nil || before < 0 {
			writeErr(w, http.StatusBadRequest, "invalid", "before is an entry id")
			return
		}
	}
	if v := q.Get("limit"); v != "" {
		if limit, err = strconv.Atoi(v); err != nil || limit < 1 || limit > 100 {
			writeErr(w, http.StatusBadRequest, "invalid", "limit is 1 to 100")
			return
		}
	}
	entries, err := s.V1.Audit(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, before, limit)
	if err != nil {
		if errors.Is(err, store.ErrForbidden) {
			writeErr(w, http.StatusForbidden, "forbidden", "only the leader can read the audit log")
			return
		}
		s.v1Fail(w, r, err)
		return
	}
	out := make([]v1AuditJSON, len(entries))
	for i, e := range entries {
		out[i] = v1AuditJSON{e.ID, e.At.UTC(), e.By, e.Action, e.Target, e.Detail}
		if len(e.Detail) == 0 || string(e.Detail) == "null" {
			out[i].Detail = nil
		}
	}
	writeJSON(w, http.StatusOK, out)
}

package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
)

// ProtocolVersion is the GeoCrew sync protocol version this server speaks.
const ProtocolVersion = "0"

type Server struct {
	Name  string
	Store *Store
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v0/info", s.info)
	mux.HandleFunc("POST /api/v0/workspaces", s.createWorkspace)
	mux.HandleFunc("POST /api/v0/workspaces/join", s.join)
	mux.HandleFunc("GET /api/v0/workspaces/{id}/members", s.members)
	return mux
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, err error) {
	writeJSON(w, status, map[string]string{"error": err.Error()})
}

func (s *Server) info(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"name": s.Name, "protocol": ProtocolVersion})
}

type joinResp struct {
	WorkspaceID   string `json:"workspace_id"`
	WorkspaceName string `json:"workspace_name"`
	MemberID      string `json:"member_id"`
	Token         string `json:"token"`
	InviteCode    string `json:"invite_code,omitempty"`
}

func (s *Server) createWorkspace(w http.ResponseWriter, r *http.Request) {
	var req struct{ Name, Nickname string }
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	ws, m, err := s.Store.CreateWorkspace(req.Name, req.Nickname)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeJSON(w, http.StatusCreated, joinResp{ws.ID, ws.Name, m.ID, m.token, ws.InviteCode})
}

func (s *Server) join(w http.ResponseWriter, r *http.Request) {
	var req struct {
		InviteCode string `json:"invite_code"`
		Nickname   string `json:"nickname"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	ws, m, err := s.Store.Join(req.InviteCode, req.Nickname)
	switch {
	case errors.Is(err, ErrBadInvite):
		writeErr(w, http.StatusNotFound, err)
		return
	case err != nil:
		writeErr(w, http.StatusBadRequest, err)
		return
	}
	writeJSON(w, http.StatusOK, joinResp{WorkspaceID: ws.ID, WorkspaceName: ws.Name, MemberID: m.ID, Token: m.token})
}

func bearer(r *http.Request) string {
	return strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
}

func (s *Server) members(w http.ResponseWriter, r *http.Request) {
	ms, err := s.Store.Members(r.PathValue("id"), bearer(r))
	if err != nil {
		writeErr(w, http.StatusNotFound, err)
		return
	}
	writeJSON(w, http.StatusOK, ms)
}

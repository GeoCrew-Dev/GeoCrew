package api

import (
	_ "embed"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"time"
)

// View links (protocol v0 §10): a member makes a read-only, expiring token
// to look at the workspace in a browser, e.g. a leader at the office.

const (
	defaultViewHours = 24
	maxViewHours     = 7 * 24
	defaultAIHours   = 8
	maxAIHours       = 24
)

type viewLinkReq struct {
	// Hours is a pointer so an explicit 0 is out of range rather than "absent".
	Hours *int `json:"hours"`
}

type viewLinkResp struct {
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expires_at"`
	// Path is the page to open on this server, token included in the
	// fragment so it never reaches access logs.
	Path string `json:"path"`
}

func (s *Server) createViewLink(w http.ResponseWriter, r *http.Request) {
	var req viewLinkReq
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil && !errors.Is(err, io.EOF) {
		writeErr(w, http.StatusBadRequest, "bad_json", "body must be JSON")
		return
	}
	hours := defaultViewHours
	if req.Hours != nil {
		hours = *req.Hours
	}
	if hours < 1 || hours > maxViewHours {
		writeErr(w, http.StatusBadRequest, "bad_hours", "hours must be between 1 and 168")
		return
	}
	m := member(r)
	ws := r.PathValue("ws")
	expires := time.Now().UTC().Add(time.Duration(hours) * time.Hour).Truncate(time.Second)
	tok, err := s.Store.CreateViewLink(r.Context(), ws, m.ID, expires)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, viewLinkResp{Token: tok, ExpiresAt: expires, Path: "/view/#ws=" + ws + "&t=" + tok})
}

func (s *Server) revokeViewLinks(w http.ResponseWriter, r *http.Request) {
	n, err := s.Store.RevokeViewLinks(r.Context(), r.PathValue("ws"), member(r).ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int{"revoked": n})
}

//go:embed web/view.html
var viewHTML []byte

// viewPage serves the browser viewer. It needs no login of its own: the
// view link's token, kept in the URL fragment, is sent as a bearer token.
func (s *Server) viewPage(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-cache")
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("X-Frame-Options", "DENY")
	// Map tiles come from OpenStreetMap, the public DEM for relief shading
	// and whatever base maps the leaders share (protocol v0 §2.2, key
	// `basemaps`), so images and fetches may go to any https host. Scripts
	// stay limited to this server and the pinned MapLibre build.
	h.Set("Content-Security-Policy", "default-src 'self'; "+
		"script-src 'self' 'unsafe-inline' https://unpkg.com; style-src 'self' 'unsafe-inline' https://unpkg.com; "+
		"img-src 'self' data: blob: https:; connect-src 'self' ws: wss: https:; "+
		"worker-src blob:; frame-ancestors 'none'")
	w.Write(viewHTML)
}

// createAILink issues an AI link (protocol v0 §11.1): MCP write tools as
// this member, for at most a day. Read-only members cannot make one.
func (s *Server) createAILink(w http.ResponseWriter, r *http.Request) {
	var req viewLinkReq
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil && !errors.Is(err, io.EOF) {
		writeErr(w, http.StatusBadRequest, "bad_json", "body must be JSON")
		return
	}
	hours := defaultAIHours
	if req.Hours != nil {
		hours = *req.Hours
	}
	if hours < 1 || hours > maxAIHours {
		writeErr(w, http.StatusBadRequest, "bad_hours", "hours must be between 1 and 24")
		return
	}
	ws := r.PathValue("ws")
	expires := time.Now().UTC().Add(time.Duration(hours) * time.Hour).Truncate(time.Second)
	tok, err := s.Store.CreateAILink(r.Context(), ws, member(r).ID, expires)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, viewLinkResp{Token: tok, ExpiresAt: expires, Path: "/api/v0/workspaces/" + ws + "/mcp"})
}

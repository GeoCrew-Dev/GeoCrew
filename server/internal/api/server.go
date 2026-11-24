// Package api implements the GeoCrew protocol v0 over HTTP and WebSocket.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/realtime"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// ProtocolVersion is the GeoCrew sync protocol version this server speaks.
const ProtocolVersion = "0"

// Capabilities advertised by /info so clients can adapt to older servers.
var Capabilities = []string{"sync", "files", "realtime", "roles", "history", "settings"}

// Server wires the store, file storage and realtime hub into HTTP handlers.
type Server struct {
	Name    string
	Version string
	Store   store.Store
	Files   *blob.Disk
	Hub     *realtime.Hub
	Log     *slog.Logger

	// MaxPushFeatures bounds one push request.
	MaxPushFeatures int
	// MaxPullFeatures bounds one pull page.
	MaxPullFeatures int
	// DisableCreate rejects new workspaces, e.g. once a team is set up.
	DisableCreate bool
	// TrustProxy keys rate limits on X-Forwarded-For (set behind a reverse proxy).
	TrustProxy bool
	// JoinPerMinute limits create and join attempts per client address (default 10).
	JoinPerMinute int
}

const maxJSONBody = 16 << 20

func (s *Server) logger() *slog.Logger {
	if s.Log != nil {
		return s.Log
	}
	return slog.Default()
}

// Handler returns the full HTTP handler.
func (s *Server) Handler() http.Handler {
	if s.MaxPushFeatures == 0 {
		s.MaxPushFeatures = 500
	}
	if s.MaxPullFeatures == 0 {
		s.MaxPullFeatures = 500
	}
	if s.Hub == nil {
		s.Hub = realtime.NewHub()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok\n")) })
	mux.HandleFunc("GET /api/v0/info", s.info)
	if s.JoinPerMinute == 0 {
		s.JoinPerMinute = 10
	}
	joins := newLimiter(s.JoinPerMinute, s.JoinPerMinute)
	mux.HandleFunc("POST /api/v0/workspaces", s.limited(joins, s.createWorkspace))
	mux.HandleFunc("POST /api/v0/workspaces/join", s.limited(joins, s.join))

	ws := "/api/v0/workspaces/{ws}"
	mux.Handle("GET "+ws, s.auth(s.workspace, anyRole))
	mux.Handle("GET "+ws+"/members", s.auth(s.members, anyRole))
	mux.Handle("PATCH "+ws+"/members/{member}", s.auth(s.setRole, leaderOnly))
	mux.Handle("DELETE "+ws+"/members/{member}", s.auth(s.revoke, leaderOnly))
	mux.Handle("POST "+ws+"/invite", s.auth(s.resetInvite, leaderOnly))
	mux.Handle("GET "+ws+"/settings", s.auth(s.settings, anyRole))
	mux.Handle("PUT "+ws+"/settings", s.auth(s.putSettings, leaderOnly))
	mux.Handle("GET "+ws+"/features", s.auth(s.pull, anyRole))
	mux.Handle("POST "+ws+"/features", s.auth(s.push, writers))
	mux.Handle("GET "+ws+"/features/{feature}/history", s.auth(s.history, anyRole))
	mux.Handle("PUT "+ws+"/files/{sha}", s.auth(s.putFile, writers))
	mux.Handle("GET "+ws+"/files/{sha}", s.auth(s.getFile, anyRole))
	mux.Handle("HEAD "+ws+"/files/{sha}", s.auth(s.getFile, anyRole))
	mux.Handle("GET "+ws+"/ws", s.auth(s.realtime, anyRole))
	return s.logRequests(mux)
}

type ctxKey struct{}

// member returns the authenticated member of the request.
func member(r *http.Request) store.Member {
	m, _ := r.Context().Value(ctxKey{}).(store.Member)
	return m
}

type roleCheck func(store.Role) bool

var (
	anyRole    roleCheck = func(store.Role) bool { return true }
	writers    roleCheck = store.Role.CanWrite
	leaderOnly roleCheck = func(r store.Role) bool { return r == store.RoleLeader }
)

// token reads the bearer token from the Authorization header, or from the
// access_token query parameter for WebSocket clients that cannot set headers.
func token(r *http.Request) string {
	if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
		return strings.TrimSpace(h[len("Bearer "):])
	}
	if r.Header.Get("Upgrade") != "" {
		return r.URL.Query().Get("access_token")
	}
	return ""
}

func (s *Server) auth(next http.HandlerFunc, allowed roleCheck) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		m, err := s.Store.Authenticate(r.Context(), r.PathValue("ws"), token(r))
		if err != nil {
			s.fail(w, r, err)
			return
		}
		if !allowed(m.Role) {
			writeErr(w, http.StatusForbidden, "forbidden", "your role does not allow this action")
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, m)))
	})
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	w.status = code
	w.ResponseWriter.WriteHeader(code)
}

// Unwrap lets http.ResponseController and the WebSocket upgrader reach the
// underlying connection.
func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(sw, r)
		// The path never contains tokens; query strings may, so they are not logged.
		s.logger().Info("request", "method", r.Method, "path", r.URL.Path, "status", sw.status,
			"duration_ms", time.Since(start).Milliseconds())
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// errorBody is the uniform error shape: a stable machine code plus a message.
type errorBody struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

func writeErr(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, errorBody{code, msg})
}

// fail maps store errors to HTTP responses.
func (s *Server) fail(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, store.ErrUnauthorized):
		writeErr(w, http.StatusUnauthorized, "unauthorized", "missing, invalid or revoked token")
	case errors.Is(err, store.ErrNotFound):
		writeErr(w, http.StatusNotFound, "not_found", "not found")
	case errors.Is(err, store.ErrBadInvite):
		writeErr(w, http.StatusNotFound, "bad_invite", "invalid invite code")
	case errors.Is(err, store.ErrInvalid):
		writeErr(w, http.StatusBadRequest, "invalid", "invalid request")
	case errors.Is(err, store.ErrStale):
		writeErr(w, http.StatusConflict, "stale", "changed by someone else; reload and try again")
	case errors.Is(err, store.ErrLastLeader):
		writeErr(w, http.StatusConflict, "last_leader", "a workspace must keep at least one leader")
	default:
		s.logger().Error("internal error", "path", r.URL.Path, "err", err)
		writeErr(w, http.StatusInternalServerError, "internal", "internal server error")
	}
}

func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxJSONBody)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", err.Error())
		return false
	}
	return true
}

type infoResp struct {
	Name           string   `json:"name"`
	Protocol       string   `json:"protocol"`
	Version        string   `json:"version"`
	Capabilities   []string `json:"capabilities"`
	MaxUploadBytes int64    `json:"max_upload_bytes"`
	MaxPushBatch   int      `json:"max_push_batch"`
}

func (s *Server) info(w http.ResponseWriter, r *http.Request) {
	var maxUpload int64
	if s.Files != nil {
		maxUpload = s.Files.MaxSize
	}
	caps := Capabilities
	if !s.DisableCreate {
		caps = append(append([]string{}, Capabilities...), "create")
	}
	writeJSON(w, http.StatusOK, infoResp{s.Name, ProtocolVersion, s.Version, caps, maxUpload, s.MaxPushFeatures})
}

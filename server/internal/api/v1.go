package api

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

// Protocol v1 (protocol/docs/protocol-v1.md), served next to v0 while the
// clients move over (M3). This file has the basics: server information,
// creating and joining a workspace, signing in with a device token
// (sections 3.3, 3.4) and the workspace itself (5.1).

// v1Capabilities are the v1 features served so far (protocol 3.3). "sync"
// comes once members and roles (5.2-5.5) are served too.
var v1Capabilities = []string{}

func (s *Server) v1Routes(mux *http.ServeMux, joins *limiter) {
	mux.HandleFunc("GET /api/v1/info", s.v1Info)
	mux.HandleFunc("POST /api/v1/workspaces", s.limited(joins, s.v1Create))
	mux.HandleFunc("POST /api/v1/join", s.limited(joins, s.v1Join))
	ws := "/api/v1/workspaces/{ws}"
	mux.Handle("GET "+ws, s.v1Auth(s.v1Workspace))
	mux.Handle("PATCH "+ws, s.v1Auth(s.v1UpdateWorkspace))
	mux.Handle("POST "+ws+"/invite/reset", s.v1Auth(s.v1ResetInvite))
	s.v1RowRoutes(mux)
}

type v1Limits struct {
	MaxPushBatch int   `json:"max_push_batch"`
	MaxPullBatch int   `json:"max_pull_batch"`
	MaxFileSize  int64 `json:"max_file_size"`
}

type v1InfoResp struct {
	Protocol     int      `json:"protocol"`
	Server       string   `json:"server"`
	Version      string   `json:"version"`
	Capabilities []string `json:"capabilities"`
	Limits       v1Limits `json:"limits"`
}

func (s *Server) v1Info(w http.ResponseWriter, r *http.Request) {
	caps := append([]string{}, v1Capabilities...)
	if !s.DisableCreate {
		caps = append(caps, "create")
	}
	writeJSON(w, http.StatusOK, v1InfoResp{1, "geocrew-server", s.Version, caps, v1Limits{s.MaxPushFeatures, v1.MaxPull, v1.MaxFileSize}})
}

type v1WorkspaceJSON struct {
	ID            string     `json:"id"`
	Name          string     `json:"name"`
	Leader        string     `json:"leader"`
	CreatedAt     time.Time  `json:"created_at"`
	ArchivedAt    *time.Time `json:"archived_at,omitempty"`
	InviteCode    string     `json:"invite_code,omitempty"`
	AccessVersion int64      `json:"access_version"`
}

type v1MemberJSON struct {
	ID       string    `json:"id"`
	Nickname string    `json:"nickname"`
	Roles    []string  `json:"roles"`
	JoinedAt time.Time `json:"joined_at"`
}

type v1Joined struct {
	Workspace v1WorkspaceJSON `json:"workspace"`
	Member    v1MemberJSON    `json:"member"`
	Token     string          `json:"token"`
}

func v1Member(m v1.Member) v1MemberJSON {
	roles := m.Roles
	if roles == nil {
		roles = []string{}
	}
	return v1MemberJSON{m.ID, m.Nickname, roles, m.JoinedAt.UTC()}
}

// v1WorkspaceFor is the workspace as member sees it: the invite code only
// for those who may manage members (protocol 5.1).
func (s *Server) v1WorkspaceFor(ctx context.Context, ws v1.Workspace, m v1.Member) (v1WorkspaceJSON, error) {
	out := v1WorkspaceJSON{ID: ws.ID, Name: ws.Name, Leader: ws.Leader, CreatedAt: ws.CreatedAt.UTC(), AccessVersion: m.AccessVersion}
	if ws.ArchivedAt != nil {
		at := ws.ArchivedAt.UTC()
		out.ArchivedAt = &at
	}
	access, err := s.V1.Access(ctx, ws.ID)
	if err != nil {
		return out, err
	}
	if access.Has(m.ID, perm.ManageMembers) {
		out.InviteCode = ws.InviteCode
	}
	return out, nil
}

func (s *Server) v1Answer(w http.ResponseWriter, r *http.Request, status int, j v1.Joined, err error) {
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	ws, err := s.v1WorkspaceFor(r.Context(), j.Workspace, j.Member)
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	writeJSON(w, status, v1Joined{ws, v1Member(j.Member), j.Token})
}

// v1Text is a name a person gives: trimmed, 1 to 64 characters.
func v1Text(s string) (string, bool) {
	s = strings.TrimSpace(s)
	n := len([]rune(s))
	return s, n >= 1 && n <= 64
}

func (s *Server) v1Create(w http.ResponseWriter, r *http.Request) {
	if s.DisableCreate {
		writeErr(w, http.StatusForbidden, "forbidden", "this server does not take new workspaces")
		return
	}
	var req struct{ Name, Nickname, Device string }
	if !decode(w, r, &req) {
		return
	}
	name, ok1 := v1Text(req.Name)
	nickname, ok2 := v1Text(req.Nickname)
	device, ok3 := v1Text(req.Device)
	if !ok1 || !ok2 || !ok3 {
		writeErr(w, http.StatusBadRequest, "invalid", "name, nickname and device are 1 to 64 characters")
		return
	}
	j, err := s.V1.CreateWorkspace(r.Context(), name, nickname, device)
	s.v1Answer(w, r, http.StatusCreated, j, err)
}

// v1Join takes an invite code, making a new member, or a transfer code,
// signing a new device in as the code's member (protocol 3.4, 5.3).
func (s *Server) v1Join(w http.ResponseWriter, r *http.Request) {
	var req struct {
		InviteCode   string `json:"invite_code"`
		TransferCode string `json:"transfer_code"`
		Nickname     string `json:"nickname"`
		Device       string `json:"device"`
	}
	if !decode(w, r, &req) {
		return
	}
	device, ok := v1Text(req.Device)
	if !ok || (req.InviteCode == "") == (req.TransferCode == "") {
		writeErr(w, http.StatusBadRequest, "invalid", "device and either invite_code or transfer_code are required")
		return
	}
	var j v1.Joined
	var err error
	if req.TransferCode != "" {
		j, err = s.V1.JoinWithTransferCode(r.Context(), req.TransferCode, device)
	} else {
		nickname, ok := v1Text(req.Nickname)
		if !ok {
			writeErr(w, http.StatusBadRequest, "invalid", "nickname is 1 to 64 characters")
			return
		}
		j, err = s.V1.Join(r.Context(), req.InviteCode, nickname, device)
	}
	s.v1Answer(w, r, http.StatusOK, j, err)
}

type v1CtxKey struct{}

type v1Caller struct {
	Member v1.Member
	Device v1.Device
}

// v1Who returns the member and device a request was signed in with.
func v1Who(r *http.Request) v1Caller {
	c, _ := r.Context().Value(v1CtxKey{}).(v1Caller)
	return c
}

// v1Auth admits requests with a working device token of the workspace in
// the path. What each member may do is checked by the store.
func (s *Server) v1Auth(next http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		m, d, err := s.V1.Authenticate(r.Context(), r.PathValue("ws"), token(r))
		if err != nil {
			s.v1Fail(w, r, err)
			return
		}
		s.v1Touch(r.Context(), d)
		next(w, r.WithContext(context.WithValue(r.Context(), v1CtxKey{}, v1Caller{m, d})))
	})
}

// v1Touch keeps a device's last activity to the minute: at most one write
// per device and minute. Failures only log.
func (s *Server) v1Touch(ctx context.Context, d v1.Device) {
	now := time.Now().UTC().Truncate(time.Minute)
	if d.LastSeenAt != nil && !now.After(*d.LastSeenAt) {
		return
	}
	if err := s.V1.Touch(ctx, d.ID, now); err != nil {
		s.logger().Warn("touch device", "err", err)
	}
}

func (s *Server) v1Workspace(w http.ResponseWriter, r *http.Request) {
	who := v1Who(r)
	ws, err := s.V1.Workspace(r.Context(), r.PathValue("ws"))
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	out, err := s.v1WorkspaceFor(r.Context(), ws, who.Member)
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) v1UpdateWorkspace(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name     *string `json:"name"`
		Archived *bool   `json:"archived"`
	}
	if !decode(w, r, &req) {
		return
	}
	if req.Name != nil {
		name, ok := v1Text(*req.Name)
		if !ok {
			writeErr(w, http.StatusBadRequest, "invalid", "name is 1 to 64 characters")
			return
		}
		req.Name = &name
	}
	who := v1Who(r)
	ws, err := s.V1.UpdateWorkspace(r.Context(), r.PathValue("ws"), who.Member.ID, v1.WorkspaceUpdate{Name: req.Name, Archived: req.Archived})
	if err != nil {
		s.v1Refused(w, r, err, perm.ManageWorkspace)
		return
	}
	out, err := s.v1WorkspaceFor(r.Context(), ws, who.Member)
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) v1ResetInvite(w http.ResponseWriter, r *http.Request) {
	code, err := s.V1.ResetInvite(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID)
	if err != nil {
		s.v1Refused(w, r, err, perm.ManageMembers)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"invite_code": code})
}

type v1Forbidden struct {
	Error      string `json:"error"`
	Message    string `json:"message"`
	Permission string `json:"permission"`
}

// v1Refused is v1Fail for a call that needs permission: a refusal names it (protocol 3.2).
func (s *Server) v1Refused(w http.ResponseWriter, r *http.Request, err error, permission string) {
	if errors.Is(err, store.ErrForbidden) {
		writeJSON(w, http.StatusForbidden, v1Forbidden{"forbidden", permission + " is required", permission})
		return
	}
	s.v1Fail(w, r, err)
}

// v1Fail maps store errors to protocol v1 errors (protocol 3.2).
func (s *Server) v1Fail(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, v1.ErrLeaderMustTransfer):
		writeErr(w, http.StatusConflict, "leader_must_transfer", "the leader must hand over before leaving")
	case errors.Is(err, v1.ErrOffsetMismatch):
		writeErr(w, http.StatusConflict, "offset_mismatch", "the chunk does not start where the upload stands")
	case errors.Is(err, v1.ErrTooLarge):
		writeErr(w, http.StatusRequestEntityTooLarge, "too_large", "the file is over max_file_size")
	case errors.Is(err, store.ErrForbidden):
		writeErr(w, http.StatusForbidden, "forbidden", "you are not allowed to do this")
	default:
		s.fail(w, r, err)
	}
}

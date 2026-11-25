package api

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/push"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

type joinResp struct {
	WorkspaceID   string     `json:"workspace_id"`
	WorkspaceName string     `json:"workspace_name"`
	MemberID      string     `json:"member_id"`
	Role          store.Role `json:"role"`
	Token         string     `json:"token"`
	InviteCode    string     `json:"invite_code,omitempty"`
}

type nameReq struct {
	Name     string `json:"name"`
	Nickname string `json:"nickname"`
}

func (s *Server) createWorkspace(w http.ResponseWriter, r *http.Request) {
	if s.DisableCreate {
		writeErr(w, http.StatusForbidden, "create_disabled", "this server does not accept new workspaces")
		return
	}
	var req nameReq
	if !decode(w, r, &req) {
		return
	}
	ws, m, tok, err := s.Store.CreateWorkspace(r.Context(), req.Name, req.Nickname)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, joinResp{ws.ID, ws.Name, m.ID, m.Role, tok, ws.InviteCode})
}

func (s *Server) join(w http.ResponseWriter, r *http.Request) {
	var req struct {
		InviteCode string `json:"invite_code"`
		Nickname   string `json:"nickname"`
	}
	if !decode(w, r, &req) {
		return
	}
	ws, m, tok, err := s.Store.Join(r.Context(), req.InviteCode, req.Nickname)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.Hub.MembersChanged(ws.ID)
	writeJSON(w, http.StatusOK, joinResp{WorkspaceID: ws.ID, WorkspaceName: ws.Name, MemberID: m.ID, Role: m.Role, Token: tok})
}

type workspaceResp struct {
	store.Workspace
	Me         store.Member `json:"me"`
	InviteCode string       `json:"invite_code,omitempty"`
	Online     []string     `json:"online"`
}

func (s *Server) workspace(w http.ResponseWriter, r *http.Request) {
	ws, err := s.Store.Workspace(r.Context(), r.PathValue("ws"))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	me := member(r)
	resp := workspaceResp{Workspace: ws, Me: me, Online: s.Hub.Online(ws.ID)}
	if me.Role == store.RoleLeader {
		resp.InviteCode = ws.InviteCode
	}
	writeJSON(w, http.StatusOK, resp)
}

// updateWorkspace renames, archives or reopens the workspace (leader only).
func (s *Server) updateWorkspace(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name     *string `json:"name"`
		Archived *bool   `json:"archived"`
	}
	if !decode(w, r, &req) {
		return
	}
	if req.Name == nil && req.Archived == nil {
		writeErr(w, http.StatusBadRequest, "invalid", "nothing to change")
		return
	}
	ws, err := s.Store.UpdateWorkspace(r.Context(), r.PathValue("ws"), store.WorkspaceUpdate{Name: req.Name, Archived: req.Archived})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.Hub.WorkspaceChanged(ws.ID, ws.Name, ws.ArchivedAt)
	writeJSON(w, http.StatusOK, workspaceResp{Workspace: ws, Me: member(r), InviteCode: ws.InviteCode, Online: s.Hub.Online(ws.ID)})
}

func (s *Server) members(w http.ResponseWriter, r *http.Request) {
	ms, err := s.Store.Members(r.Context(), r.PathValue("ws"))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if ms == nil {
		ms = []store.Member{}
	}
	writeJSON(w, http.StatusOK, ms)
}

func (s *Server) resetInvite(w http.ResponseWriter, r *http.Request) {
	code, err := s.Store.ResetInvite(r.Context(), r.PathValue("ws"))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"invite_code": code})
}

func (s *Server) setRole(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Role store.Role `json:"role"`
	}
	if !decode(w, r, &req) {
		return
	}
	ws := r.PathValue("ws")
	if err := s.Store.SetRole(r.Context(), ws, r.PathValue("member"), req.Role); err != nil {
		s.fail(w, r, err)
		return
	}
	s.Hub.MembersChanged(ws)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) revoke(w http.ResponseWriter, r *http.Request) {
	ws, target := r.PathValue("ws"), r.PathValue("member")
	if err := s.Store.RevokeMember(r.Context(), ws, target); err != nil {
		s.fail(w, r, err)
		return
	}
	s.Hub.Kick(ws, target)
	s.Hub.MembersChanged(ws)
	w.WriteHeader(http.StatusNoContent)
}

type settingsResp struct {
	Version  int64           `json:"version"`
	Settings json.RawMessage `json:"settings"`
}

// settings returns the workspace's shared settings, e.g. the record form
// every member fills in. The server stores them without interpreting them.
func (s *Server) settings(w http.ResponseWriter, r *http.Request) {
	raw, v, err := s.Store.Settings(r.Context(), r.PathValue("ws"))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, settingsResp{v, raw})
}

func (s *Server) putSettings(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Settings    json.RawMessage `json:"settings"`
		BaseVersion *int64          `json:"base_version"`
	}
	if !decode(w, r, &req) {
		return
	}
	if len(req.Settings) > store.MaxSettingsBytes {
		writeErr(w, http.StatusRequestEntityTooLarge, "too_large", "settings exceed 64 KiB")
		return
	}
	base := int64(-1)
	if req.BaseVersion != nil {
		base = *req.BaseVersion
	}
	ws := r.PathValue("ws")
	before, _, err := s.Store.Settings(r.Context(), ws)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	v, err := s.Store.PutSettings(r.Context(), ws, req.Settings, base)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.Hub.SettingsChanged(ws, v)
	if s.Push != nil && newNotice(before, req.Settings) {
		s.Push.Notify(push.Notice{Type: push.EventNotice, Workspace: ws, From: member(r).ID, Seq: v}, push.Audience{Except: member(r).ID})
	}
	writeJSON(w, http.StatusOK, settingsResp{v, req.Settings})
}

// noticeKey is the part of the shared settings' notice (protocol §2.2) that
// tells one posting from another.
type noticeKey struct {
	Text      string `json:"text"`
	UpdatedAt string `json:"updated_at"`
}

func noticeIn(settings json.RawMessage) noticeKey {
	var s struct {
		Notice *noticeKey `json:"notice"`
	}
	if json.Unmarshal(settings, &s) != nil || s.Notice == nil {
		return noticeKey{}
	}
	s.Notice.Text = strings.TrimSpace(s.Notice.Text)
	return *s.Notice
}

// newNotice reports whether after carries a non-empty notice that before
// did not, so members hear about a new or changed notice but not about other
// settings edits or a notice taken down.
func newNotice(before, after json.RawMessage) bool {
	n := noticeIn(after)
	return n.Text != "" && n != noticeIn(before)
}

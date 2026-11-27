package api

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

// Protocol v1 members (5.2) and devices (5.3). The store checks who may do
// what (protocol 4.5); refusals name the permission when one would allow it.

func (s *Server) v1MemberRoutes(mux *http.ServeMux) {
	ws := "/api/v1/workspaces/{ws}"
	mux.Handle("GET "+ws+"/members", s.v1Auth(s.v1Members))
	mux.Handle("PATCH "+ws+"/members/{member}", s.v1Auth(s.v1UpdateMember))
	mux.Handle("DELETE "+ws+"/members/{member}", s.v1Auth(s.v1RemoveMember))
	mux.Handle("POST "+ws+"/leader", s.v1Auth(s.v1TransferLeader))
	mux.Handle("GET "+ws+"/members/{member}/devices", s.v1Auth(s.v1Devices))
	mux.Handle("DELETE "+ws+"/devices/{device}", s.v1Auth(s.v1RevokeDevice))
	mux.Handle("POST "+ws+"/members/{member}/transfer-code", s.v1Auth(s.v1TransferCode))
}

type v1DeviceJSON struct {
	ID         string     `json:"id"`
	Device     string     `json:"device"`
	CreatedAt  time.Time  `json:"created_at"`
	LastSeenAt *time.Time `json:"last_seen_at,omitempty"`
}

func (s *Server) v1Members(w http.ResponseWriter, r *http.Request) {
	members, err := s.V1.Members(r.Context(), r.PathValue("ws"))
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	out := make([]v1MemberJSON, len(members))
	for i, m := range members {
		out[i] = v1Member(m)
	}
	writeJSON(w, http.StatusOK, out)
}

// v1UpdateMember changes a member's own nickname, and the roles of a member
// the caller can manage (protocol 5.2).
func (s *Server) v1UpdateMember(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Nickname *string   `json:"nickname"`
		Roles    *[]string `json:"roles"`
	}
	if !decode(w, r, &req) {
		return
	}
	ws, target, actor := r.PathValue("ws"), r.PathValue("member"), v1Who(r).Member.ID
	if req.Nickname == nil && req.Roles == nil {
		writeErr(w, http.StatusBadRequest, "invalid", "nickname or roles is required")
		return
	}
	if req.Nickname != nil && target != actor {
		writeErr(w, http.StatusForbidden, "forbidden", "only your own nickname can be changed")
		return
	}
	// Checked before anything changes: a request is done whole or not at all.
	if req.Nickname != nil {
		if n := len([]rune(strings.TrimSpace(*req.Nickname))); n < 1 || n > 32 {
			writeErr(w, http.StatusBadRequest, "invalid", "nickname is 1 to 32 characters")
			return
		}
	}
	var m v1.Member
	var err error
	if req.Roles != nil {
		roles := *req.Roles
		if roles == nil {
			roles = []string{}
		}
		if m, err = s.V1.SetMemberRoles(r.Context(), ws, actor, target, roles); err != nil {
			s.v1Refused(w, r, err, perm.ManageMembers)
			return
		}
	}
	if req.Nickname != nil {
		if m, err = s.V1.SetNickname(r.Context(), ws, actor, *req.Nickname); err != nil {
			s.v1Fail(w, r, err)
			return
		}
	}
	writeJSON(w, http.StatusOK, v1Member(m))
}

// v1RemoveMember takes a member out, or the caller themselves: leaving is a
// safety right no setting takes away (protocol 4.2, 5.2).
func (s *Server) v1RemoveMember(w http.ResponseWriter, r *http.Request) {
	if err := s.V1.RemoveMember(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, r.PathValue("member")); err != nil {
		s.v1Refused(w, r, err, perm.ManageMembers)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) v1TransferLeader(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Member string `json:"member"`
	}
	if !decode(w, r, &req) {
		return
	}
	if req.Member == "" {
		writeErr(w, http.StatusBadRequest, "invalid", "member is required")
		return
	}
	who := v1Who(r)
	if err := s.V1.TransferLeader(r.Context(), r.PathValue("ws"), who.Member.ID, req.Member); err != nil {
		if errors.Is(err, store.ErrForbidden) {
			writeErr(w, http.StatusForbidden, "forbidden", "only the leader can hand over")
			return
		}
		s.v1Fail(w, r, err)
		return
	}
	s.v1Workspace(w, r)
}

func (s *Server) v1Devices(w http.ResponseWriter, r *http.Request) {
	devices, err := s.V1.Devices(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, r.PathValue("member"))
	if err != nil {
		s.v1Refused(w, r, err, perm.ManageMembers)
		return
	}
	out := make([]v1DeviceJSON, len(devices))
	for i, d := range devices {
		out[i] = v1DeviceJSON{d.ID, d.Name, d.CreatedAt.UTC(), d.LastSeenAt}
		if d.LastSeenAt != nil {
			at := d.LastSeenAt.UTC()
			out[i].LastSeenAt = &at
		}
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) v1RevokeDevice(w http.ResponseWriter, r *http.Request) {
	if err := s.V1.RevokeDevice(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, r.PathValue("device")); err != nil {
		s.v1Refused(w, r, err, perm.ManageMembers)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// v1TransferCode issues a one-time code that signs a new phone in as the
// member; only the member and the leader may (protocol 5.3).
func (s *Server) v1TransferCode(w http.ResponseWriter, r *http.Request) {
	code, expires, err := s.V1.CreateTransferCode(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, r.PathValue("member"))
	if err != nil {
		if errors.Is(err, store.ErrForbidden) {
			writeErr(w, http.StatusForbidden, "forbidden", "only the member and the leader can issue a transfer code")
			return
		}
		s.v1Fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"code": code, "expires_at": expires.UTC()})
}

// Package v1test is the behaviour every v1.Store backend must have. A
// backend's tests call [Run] with a function that opens an empty store.
package v1test

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

// Opener returns a new, empty store that reads the time from now and is
// closed when the test ends.
type Opener func(t *testing.T, now func() time.Time) v1.Store

// Run checks a backend against the v1 storage contract.
func Run(t *testing.T, open Opener) {
	for _, c := range []struct {
		name string
		fn   func(*testing.T, Opener)
	}{
		{"CreateAndJoin", createAndJoin},
		{"Authenticate", authenticate},
		{"Access", access},
	} {
		t.Run(c.name, func(t *testing.T) { c.fn(t, open) })
	}
}

// clock starts at a fixed time and moves on a second at every reading, so
// each event gets its own time.
func clock() func() time.Time {
	var mu sync.Mutex
	at := time.Date(2026, 11, 26, 8, 0, 0, 0, time.UTC)
	return func() time.Time {
		mu.Lock()
		defer mu.Unlock()
		at = at.Add(time.Second)
		return at
	}
}

func createAndJoin(t *testing.T, open Opener) {
	ctx := context.Background()
	s := open(t, clock())
	for _, bad := range [][3]string{{"", "a", "d"}, {"w", " ", "d"}, {"w", "a", ""}, {strings.Repeat("名", 65), "a", "d"}} {
		if _, err := s.CreateWorkspace(ctx, bad[0], bad[1], bad[2]); !errors.Is(err, store.ErrInvalid) {
			t.Fatalf("create %q: %v", bad, err)
		}
	}
	lead, err := s.CreateWorkspace(ctx, " 野外一组 ", "组长", "Galaxy S24")
	if err != nil {
		t.Fatal(err)
	}
	ws := lead.Workspace
	if ws.Name != "野外一组" || ws.Leader != lead.Member.ID || len(ws.InviteCode) != 8 || ws.ArchivedAt != nil ||
		lead.Token == "" || lead.Device.MemberID != lead.Member.ID || lead.Device.Name != "Galaxy S24" {
		t.Fatalf("created %+v", lead)
	}

	if _, err := s.Join(ctx, "NOSUCHCO", "x", "d"); !errors.Is(err, store.ErrBadInvite) {
		t.Fatalf("unknown code: %v", err)
	}
	if _, err := s.Join(ctx, ws.InviteCode, "", "d"); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("no nickname: %v", err)
	}
	// Codes are read aloud in the field: case and spaces do not matter.
	mem, err := s.Join(ctx, " "+strings.ToLower(ws.InviteCode)+" ", "组员甲", "Mate 60")
	if err != nil {
		t.Fatal(err)
	}
	if mem.Workspace.ID != ws.ID || mem.Member.ID == lead.Member.ID || len(mem.Member.Roles) != 0 || mem.Token == lead.Token {
		t.Fatalf("joined %+v", mem)
	}
	// Joining with the invite code again makes a new member: a new phone of
	// the same person goes through a transfer code instead.
	again, err := s.Join(ctx, ws.InviteCode, "组员甲", "Mate 60")
	if err != nil || again.Member.ID == mem.Member.ID {
		t.Fatalf("joined again: %v %+v", err, again)
	}

	got, err := s.Workspace(ctx, ws.ID)
	if err != nil || got.InviteCode != ws.InviteCode || got.Leader != lead.Member.ID || !got.CreatedAt.Equal(ws.CreatedAt) {
		t.Fatalf("workspace: %v %+v", err, got)
	}
	if _, err := s.Workspace(ctx, "nope"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("unknown workspace: %v", err)
	}
	ms, err := s.Members(ctx, ws.ID)
	if err != nil || len(ms) != 3 || ms[0].ID != lead.Member.ID || ms[1].ID != mem.Member.ID || ms[2].ID != again.Member.ID {
		t.Fatalf("members: %v %+v", err, ms)
	}
	if !ms[0].JoinedAt.Equal(lead.Member.JoinedAt) || ms[1].Nickname != "组员甲" || ms[1].Roles == nil || ms[1].AccessVersion != 1 {
		t.Fatalf("member details: %+v", ms)
	}
	if _, err := s.Members(ctx, "nope"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("members of an unknown workspace: %v", err)
	}
}

func authenticate(t *testing.T, open Opener) {
	ctx := context.Background()
	s := open(t, clock())
	a, err := s.CreateWorkspace(ctx, "甲", "a", "phone a")
	if err != nil {
		t.Fatal(err)
	}
	b, err := s.CreateWorkspace(ctx, "乙", "b", "phone b")
	if err != nil {
		t.Fatal(err)
	}
	m, d, err := s.Authenticate(ctx, a.Workspace.ID, a.Token)
	if err != nil || m.ID != a.Member.ID || m.WorkspaceID != a.Workspace.ID || m.Nickname != "a" || m.AccessVersion != 1 ||
		d.ID != a.Device.ID || d.MemberID != a.Member.ID || d.Name != "phone a" || !d.CreatedAt.Equal(a.Device.CreatedAt) {
		t.Fatalf("authenticate: %v %+v %+v", err, m, d)
	}
	for _, c := range []struct{ ws, token string }{
		{a.Workspace.ID, ""}, {a.Workspace.ID, "nope"}, {a.Workspace.ID, b.Token}, {b.Workspace.ID, a.Token},
	} {
		if _, _, err := s.Authenticate(ctx, c.ws, c.token); !errors.Is(err, store.ErrUnauthorized) {
			t.Fatalf("token %q for %s: %v", c.token, c.ws, err)
		}
	}
}

func access(t *testing.T, open Opener) {
	ctx := context.Background()
	s := open(t, clock())
	lead, err := s.CreateWorkspace(ctx, "w", "lead", "p")
	if err != nil {
		t.Fatal(err)
	}
	mem, err := s.Join(ctx, lead.Workspace.InviteCode, "mem", "p")
	if err != nil {
		t.Fatal(err)
	}
	w, err := s.Access(ctx, lead.Workspace.ID)
	if err != nil {
		t.Fatal(err)
	}
	if w.Leader != lead.Member.ID || len(w.Members) != 2 || len(w.Roles) != 1 {
		t.Fatalf("access %+v", w)
	}
	base, ok := w.Roles[perm.Base]
	if !ok || base.Position != 0 || len(base.Permissions) != len(v1.DefaultBasePermissions) {
		t.Fatalf("base role %+v", base)
	}
	for p, l := range v1.DefaultBasePermissions {
		if base.Permissions[p] != l {
			t.Fatalf("base %s = %d, want %d", p, base.Permissions[p], l)
		}
	}
	// The rules read the defaults: members edit and talk; only the leader manages.
	m, l := mem.Member.ID, lead.Member.ID
	if !w.Has(m, perm.SendMessages) || w.Has(m, perm.ManageMembers) || !w.Has(l, perm.ManageMembers) ||
		!w.CanTouchRow(m, perm.EditRows, "any layer", l) || w.CanManageMember(m, l) || !w.CanManageMember(l, m) {
		t.Fatal("the rules do not read the stored roles")
	}
	if _, err := s.Access(ctx, "nope"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("access to an unknown workspace: %v", err)
	}
}

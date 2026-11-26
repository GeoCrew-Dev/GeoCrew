// Package v1test is the behaviour every v1.Store backend must have. A
// backend's tests call [Run] with a function that opens an empty store.
package v1test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/merge"
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
		{"UpdateWorkspace", updateWorkspace},
		{"RemoveAndLeave", removeAndLeave},
		{"TransferLeader", transferLeader},
		{"Devices", devices},
		{"TransferCode", transferCode},
		{"TransferCodeRace", transferCodeRace},
		{"AuditLog", auditLog},
		{"Roles", roles},
		{"Layers", layers},
		{"Overrides", overrides},
		{"PushRows", pushRows},
		{"PushLayerProps", pushLayerProps},
	} {
		t.Run(c.name, func(t *testing.T) { c.fn(t, open) })
	}
}

// testClock starts at a fixed time and moves on a second at every reading,
// so each event gets its own time; Advance jumps ahead.
type testClock struct {
	mu sync.Mutex
	at time.Time
}

func newClock() *testClock { return &testClock{at: time.Date(2026, 11, 26, 8, 0, 0, 0, time.UTC)} }

func (c *testClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.at = c.at.Add(time.Second)
	return c.at
}

func (c *testClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.at = c.at.Add(d)
}

func clock() func() time.Time { return newClock().Now }

// team is a workspace with a leader and two members, each on one device.
type team struct {
	s          v1.Store
	ws         string
	lead, a, b v1.Joined
}

func newTeam(t *testing.T, s v1.Store) team {
	t.Helper()
	ctx := context.Background()
	lead, err := s.CreateWorkspace(ctx, "队", "lead", "lead phone")
	if err != nil {
		t.Fatal(err)
	}
	a, err := s.Join(ctx, lead.Workspace.InviteCode, "a", "a phone")
	if err != nil {
		t.Fatal(err)
	}
	b, err := s.Join(ctx, lead.Workspace.InviteCode, "b", "b phone")
	if err != nil {
		t.Fatal(err)
	}
	return team{s: s, ws: lead.Workspace.ID, lead: lead, a: a, b: b}
}

// works reports whether a token still signs in.
func (tm team) works(t *testing.T, token string) bool {
	t.Helper()
	_, _, err := tm.s.Authenticate(context.Background(), tm.ws, token)
	if err != nil && !errors.Is(err, store.ErrUnauthorized) {
		t.Fatal(err)
	}
	return err == nil
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

func updateWorkspace(t *testing.T, open Opener) {
	ctx := context.Background()
	tm := newTeam(t, open(t, clock()))
	s, l, a := tm.s, tm.lead.Member.ID, tm.a.Member.ID
	name, yes, no := "新名字", true, false
	if _, err := s.UpdateWorkspace(ctx, tm.ws, a, v1.WorkspaceUpdate{Name: &name}); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a member renames: %v", err)
	}
	empty := " "
	if _, err := s.UpdateWorkspace(ctx, tm.ws, l, v1.WorkspaceUpdate{Name: &empty}); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("empty name: %v", err)
	}
	ws, err := s.UpdateWorkspace(ctx, tm.ws, l, v1.WorkspaceUpdate{Name: &name, Archived: &yes})
	if err != nil || ws.Name != name || ws.ArchivedAt == nil {
		t.Fatalf("rename and archive: %v %+v", err, ws)
	}
	again, err := s.UpdateWorkspace(ctx, tm.ws, l, v1.WorkspaceUpdate{Archived: &yes})
	if err != nil || !again.ArchivedAt.Equal(*ws.ArchivedAt) {
		t.Fatalf("archiving again keeps the time: %v %+v", err, again)
	}
	if _, err := s.Join(ctx, ws.InviteCode, "c", "p"); !errors.Is(err, store.ErrArchived) {
		t.Fatalf("join an archived workspace: %v", err)
	}
	if got, _ := s.Workspace(ctx, tm.ws); got.Name != name || got.ArchivedAt == nil || !got.ArchivedAt.Equal(*ws.ArchivedAt) {
		t.Fatalf("stored %+v", got)
	}
	if ws, err := s.UpdateWorkspace(ctx, tm.ws, l, v1.WorkspaceUpdate{Archived: &no}); err != nil || ws.ArchivedAt != nil {
		t.Fatalf("reopen: %v %+v", err, ws)
	}

	if _, err := s.ResetInvite(ctx, tm.ws, a); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a member resets the code: %v", err)
	}
	code, err := s.ResetInvite(ctx, tm.ws, l)
	if err != nil || code == ws.InviteCode || len(code) != 8 {
		t.Fatalf("reset: %v %q", err, code)
	}
	if _, err := s.Join(ctx, ws.InviteCode, "c", "p"); !errors.Is(err, store.ErrBadInvite) {
		t.Fatalf("old code: %v", err)
	}
	if _, err := s.Join(ctx, code, "c", "p"); err != nil {
		t.Fatalf("new code: %v", err)
	}
	if _, err := s.UpdateWorkspace(ctx, "nope", l, v1.WorkspaceUpdate{Name: &name}); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("unknown workspace: %v", err)
	}

	m, err := s.SetNickname(ctx, tm.ws, a, " 阿甲 ")
	if err != nil || m.Nickname != "阿甲" || m.ID != a {
		t.Fatalf("nickname: %v %+v", err, m)
	}
	if _, err := s.SetNickname(ctx, tm.ws, a, ""); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("empty nickname: %v", err)
	}
}

func removeAndLeave(t *testing.T, open Opener) {
	ctx := context.Background()
	tm := newTeam(t, open(t, clock()))
	s, l, a, b := tm.s, tm.lead.Member.ID, tm.a.Member.ID, tm.b.Member.ID
	if err := s.RemoveMember(ctx, tm.ws, a, b); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a member removes another: %v", err)
	}
	if err := s.RemoveMember(ctx, tm.ws, a, l); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a member removes the leader: %v", err)
	}
	if err := s.RemoveMember(ctx, tm.ws, l, l); !errors.Is(err, v1.ErrLeaderMustTransfer) {
		t.Fatalf("the leader leaves: %v", err)
	}
	if err := s.RemoveMember(ctx, tm.ws, l, "nope"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("remove nobody: %v", err)
	}
	if err := s.RemoveMember(ctx, tm.ws, l, a); err != nil {
		t.Fatal(err)
	}
	if tm.works(t, tm.a.Token) {
		t.Fatal("a removed member's token still works")
	}
	if err := s.RemoveMember(ctx, tm.ws, l, a); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("remove twice: %v", err)
	}
	// Leaving is a safety right: no permission needed.
	if err := s.RemoveMember(ctx, tm.ws, b, b); err != nil {
		t.Fatalf("leave: %v", err)
	}
	if tm.works(t, tm.b.Token) {
		t.Fatal("the token of someone who left still works")
	}
	ms, err := s.Members(ctx, tm.ws)
	if err != nil || len(ms) != 1 || ms[0].ID != l {
		t.Fatalf("members after: %v %+v", err, ms)
	}
	if _, err := s.SetNickname(ctx, tm.ws, a, "x"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("a removed member renames: %v", err)
	}
}

func transferLeader(t *testing.T, open Opener) {
	ctx := context.Background()
	tm := newTeam(t, open(t, clock()))
	s, l, a := tm.s, tm.lead.Member.ID, tm.a.Member.ID
	if err := s.TransferLeader(ctx, tm.ws, a, a); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a member takes over: %v", err)
	}
	if err := s.TransferLeader(ctx, tm.ws, l, l); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("hand over to oneself: %v", err)
	}
	if err := s.TransferLeader(ctx, tm.ws, l, "nope"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("hand over to nobody: %v", err)
	}
	if err := s.TransferLeader(ctx, tm.ws, l, a); err != nil {
		t.Fatal(err)
	}
	ws, _ := s.Workspace(ctx, tm.ws)
	if ws.Leader != a {
		t.Fatalf("leader is %s, want %s", ws.Leader, a)
	}
	ms, _ := s.Members(ctx, tm.ws)
	for _, m := range ms {
		want := int64(1)
		if m.ID == l || m.ID == a {
			want = 2
		}
		if m.AccessVersion != want {
			t.Fatalf("%s access version %d, want %d", m.Nickname, m.AccessVersion, want)
		}
	}
	if err := s.RemoveMember(ctx, tm.ws, a, a); !errors.Is(err, v1.ErrLeaderMustTransfer) {
		t.Fatalf("the new leader leaves: %v", err)
	}
	if err := s.RemoveMember(ctx, tm.ws, l, l); err != nil {
		t.Fatalf("the old leader leaves: %v", err)
	}
}

func devices(t *testing.T, open Opener) {
	ctx := context.Background()
	tm := newTeam(t, open(t, clock()))
	s, l, a, b := tm.s, tm.lead.Member.ID, tm.a.Member.ID, tm.b.Member.ID
	ds, err := s.Devices(ctx, tm.ws, a, a)
	if err != nil || len(ds) != 1 || ds[0].ID != tm.a.Device.ID || ds[0].Name != "a phone" || ds[0].LastSeenAt != nil {
		t.Fatalf("own devices: %v %+v", err, ds)
	}
	if _, err := s.Devices(ctx, tm.ws, b, a); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("another member's devices: %v", err)
	}
	if ds, err := s.Devices(ctx, tm.ws, l, a); err != nil || len(ds) != 1 {
		t.Fatalf("the leader reads them: %v %+v", err, ds)
	}
	seen := time.Date(2026, 11, 26, 9, 30, 42, 0, time.UTC)
	if err := s.Touch(ctx, tm.a.Device.ID, seen); err != nil {
		t.Fatal(err)
	}
	if ds, _ := s.Devices(ctx, tm.ws, a, a); ds[0].LastSeenAt == nil || !ds[0].LastSeenAt.Equal(seen.Truncate(time.Minute)) {
		t.Fatalf("last seen: %+v", ds[0])
	}
	if err := s.RevokeDevice(ctx, tm.ws, b, tm.a.Device.ID); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("another member revokes: %v", err)
	}
	if err := s.RevokeDevice(ctx, tm.ws, a, "nope"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("revoke nothing: %v", err)
	}
	if err := s.RevokeDevice(ctx, tm.ws, l, tm.b.Device.ID); err != nil {
		t.Fatalf("the leader revokes: %v", err)
	}
	if tm.works(t, tm.b.Token) {
		t.Fatal("a revoked token still works")
	}
	if ds, _ := s.Devices(ctx, tm.ws, b, b); len(ds) != 0 {
		t.Fatalf("revoked devices are not listed: %+v", ds)
	}
	if err := s.RevokeDevice(ctx, tm.ws, a, tm.a.Device.ID); err != nil {
		t.Fatalf("revoke one's own: %v", err)
	}
	if tm.works(t, tm.a.Token) {
		t.Fatal("a revoked token still works")
	}
}

func transferCode(t *testing.T, open Opener) {
	ctx := context.Background()
	c := newClock()
	tm := newTeam(t, open(t, c.Now))
	s, l, a, b := tm.s, tm.lead.Member.ID, tm.a.Member.ID, tm.b.Member.ID
	if _, _, err := s.CreateTransferCode(ctx, tm.ws, b, a); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("another member makes a code: %v", err)
	}
	code, expires, err := s.CreateTransferCode(ctx, tm.ws, a, a)
	if err != nil || len(code) != 12 || expires.Sub(c.Now()) > v1.TransferCodeTTL || expires.Sub(c.Now()) < v1.TransferCodeTTL-time.Minute {
		t.Fatalf("code: %v %q %v", err, code, expires)
	}
	if _, err := s.JoinWithTransferCode(ctx, "WRONGWRONG22", "new phone"); !errors.Is(err, store.ErrBadInvite) {
		t.Fatalf("wrong code: %v", err)
	}
	// Typed with spaces and in lower case, as people do.
	typed := strings.ToLower(code[:4] + " " + code[4:8] + "-" + code[8:])
	j, err := s.JoinWithTransferCode(ctx, typed, "new phone")
	if err != nil || j.Member.ID != a || j.Workspace.ID != tm.ws || j.Device.ID == tm.a.Device.ID || j.Token == tm.a.Token {
		t.Fatalf("transfer: %v %+v", err, j)
	}
	if !tm.works(t, j.Token) || !tm.works(t, tm.a.Token) {
		t.Fatal("both phones should sign in")
	}
	if _, err := s.JoinWithTransferCode(ctx, code, "third phone"); !errors.Is(err, store.ErrBadInvite) {
		t.Fatalf("used twice: %v", err)
	}
	if ds, _ := s.Devices(ctx, tm.ws, a, a); len(ds) != 2 {
		t.Fatalf("devices after the transfer: %+v", ds)
	}

	// The leader makes one for a member who lost their phone; a newer code replaces it.
	old, _, err := s.CreateTransferCode(ctx, tm.ws, l, b)
	if err != nil {
		t.Fatal(err)
	}
	fresh, _, err := s.CreateTransferCode(ctx, tm.ws, b, b)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.JoinWithTransferCode(ctx, old, "p"); !errors.Is(err, store.ErrBadInvite) {
		t.Fatalf("replaced code: %v", err)
	}
	c.Advance(v1.TransferCodeTTL)
	if _, err := s.JoinWithTransferCode(ctx, fresh, "p"); !errors.Is(err, store.ErrBadInvite) {
		t.Fatalf("expired code: %v", err)
	}
	// Removing a member ends their codes.
	late, _, err := s.CreateTransferCode(ctx, tm.ws, b, b)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.RemoveMember(ctx, tm.ws, l, b); err != nil {
		t.Fatal(err)
	}
	if _, err := s.JoinWithTransferCode(ctx, late, "p"); !errors.Is(err, store.ErrBadInvite) {
		t.Fatalf("code of a removed member: %v", err)
	}
}

func auditLog(t *testing.T, open Opener) {
	ctx := context.Background()
	tm := newTeam(t, open(t, clock()))
	s, l, a, b := tm.s, tm.lead.Member.ID, tm.a.Member.ID, tm.b.Member.ID
	if err := s.RevokeDevice(ctx, tm.ws, a, tm.a.Device.ID); err != nil {
		t.Fatal(err)
	}
	if err := s.RemoveMember(ctx, tm.ws, l, b); err != nil {
		t.Fatal(err)
	}
	if err := s.RemoveMember(ctx, tm.ws, a, a); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Audit(ctx, tm.ws, a, 0, 10); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a member reads the log: %v", err)
	}
	log, err := s.Audit(ctx, tm.ws, l, 0, 0)
	if err != nil || len(log) != 3 {
		t.Fatalf("log: %v %+v", err, log)
	}
	want := []struct{ action, by, target string }{
		{v1.AuditLeave, a, a}, {v1.AuditRemoveMember, l, b}, {v1.AuditRevokeDevice, a, tm.a.Device.ID},
	}
	for i, w := range want {
		e := log[i]
		if e.Action != w.action || e.By != w.by || e.Target != w.target || e.At.IsZero() || len(e.Detail) == 0 {
			t.Fatalf("entry %d: %+v, want %+v", i, e, w)
		}
	}
	if !log[0].At.After(log[1].At) || log[0].ID <= log[1].ID {
		t.Fatalf("newest first: %+v", log)
	}
	page, err := s.Audit(ctx, tm.ws, l, log[0].ID, 1)
	if err != nil || len(page) != 1 || page[0].ID != log[1].ID {
		t.Fatalf("next page: %v %+v", err, page)
	}
}

// Two phones racing with one transfer code: only one signs in.
func transferCodeRace(t *testing.T, open Opener) {
	ctx := context.Background()
	tm := newTeam(t, open(t, clock()))
	code, _, err := tm.s.CreateTransferCode(ctx, tm.ws, tm.a.Member.ID, tm.a.Member.ID)
	if err != nil {
		t.Fatal(err)
	}
	const phones = 8
	errs := make(chan error, phones)
	for i := 0; i < phones; i++ {
		go func() {
			_, err := tm.s.JoinWithTransferCode(ctx, code, "phone")
			errs <- err
		}()
	}
	won := 0
	for i := 0; i < phones; i++ {
		switch err := <-errs; {
		case err == nil:
			won++
		case !errors.Is(err, store.ErrBadInvite):
			t.Fatalf("racing phone: %v", err)
		}
	}
	if won != 1 {
		t.Fatalf("%d phones signed in with one code", won)
	}
}

// versions maps member ids to their access versions.
func (tm team) versions(t *testing.T) map[string]int64 {
	t.Helper()
	ms, err := tm.s.Members(context.Background(), tm.ws)
	if err != nil {
		t.Fatal(err)
	}
	out := map[string]int64{}
	for _, m := range ms {
		out[m.ID] = m.AccessVersion
	}
	return out
}

// bumped lists who of ids got a new access version since before.
func (tm team) bumped(t *testing.T, before map[string]int64) map[string]bool {
	t.Helper()
	out := map[string]bool{}
	for id, v := range tm.versions(t) {
		if v != before[id] {
			out[id] = true
		}
	}
	return out
}

func roles(t *testing.T, open Opener) {
	ctx := context.Background()
	tm := newTeam(t, open(t, clock()))
	s, l, a, b := tm.s, tm.lead.Member.ID, tm.a.Member.ID, tm.b.Member.ID
	rs, err := s.Roles(ctx, tm.ws)
	if err != nil || len(rs) != 1 || rs[0].ID != perm.Base || rs[0].Name != v1.BaseRoleName || rs[0].Version != 1 {
		t.Fatalf("roles of a new workspace: %v %+v", err, rs)
	}

	deputyPerms := map[string]perm.Level{perm.ManageMembers: perm.All, perm.ManageRoles: perm.All, perm.EditRows: perm.All, perm.ExportData: perm.All}
	if _, err := s.CreateRole(ctx, tm.ws, a, "副组长", 30, deputyPerms); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a member makes a role: %v", err)
	}
	for _, bad := range []struct {
		name  string
		pos   int
		perms map[string]perm.Level
	}{
		{"x", 0, nil},
		{"", 5, nil},
		{"x", 5, map[string]perm.Level{"fly": perm.All}},
		{"x", 5, map[string]perm.Level{perm.ViewLayer: perm.None}}, // per layer only
		{"x", 5, map[string]perm.Level{perm.SendMessages: perm.Own}},
	} {
		if _, err := s.CreateRole(ctx, tm.ws, l, bad.name, bad.pos, bad.perms); !errors.Is(err, store.ErrInvalid) {
			t.Fatalf("role %+v: %v", bad, err)
		}
	}
	deputy, err := s.CreateRole(ctx, tm.ws, l, "副组长", 30, deputyPerms)
	if err != nil || deputy.ID == "" || deputy.Version != 1 || deputy.Permissions[perm.ManageRoles] != perm.All {
		t.Fatalf("create: %v %+v", err, deputy)
	}

	before := tm.versions(t)
	m, err := s.SetMemberRoles(ctx, tm.ws, l, a, []string{deputy.ID, deputy.ID})
	if err != nil || len(m.Roles) != 1 || m.Roles[0] != deputy.ID {
		t.Fatalf("give a the deputy role: %v %+v", err, m)
	}
	if got := tm.bumped(t, before); !got[a] || len(got) != 1 {
		t.Fatalf("access versions bumped for %v, want only a", got)
	}
	if _, err := s.SetMemberRoles(ctx, tm.ws, l, a, []string{perm.Base}); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("the base role is not given: %v", err)
	}
	if _, err := s.SetMemberRoles(ctx, tm.ws, l, a, []string{"nope"}); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("an unknown role: %v", err)
	}

	// The deputy works below their own place, with what they hold.
	if _, err := s.CreateRole(ctx, tm.ws, a, "并列", 30, nil); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a role at the deputy's own place: %v", err)
	}
	if _, err := s.CreateRole(ctx, tm.ws, a, "插件", 10, map[string]perm.Level{perm.ManagePlugins: perm.All}); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a permission the deputy lacks: %v", err)
	}
	sampler, err := s.CreateRole(ctx, tm.ws, a, "采样员", 20, map[string]perm.Level{perm.EditRows: perm.All})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetMemberRoles(ctx, tm.ws, a, b, []string{deputy.ID}); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("give a role at one's own place: %v", err)
	}
	if _, err := s.SetMemberRoles(ctx, tm.ws, a, l, []string{sampler.ID}); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("set the leader's roles: %v", err)
	}
	if _, err := s.SetMemberRoles(ctx, tm.ws, a, a, nil); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("set one's own roles: %v", err)
	}
	if _, err := s.SetMemberRoles(ctx, tm.ws, a, b, []string{sampler.ID}); err != nil {
		t.Fatalf("give a role below: %v", err)
	}

	// Updates go by version and stay within what the actor holds.
	newName := "采样"
	if cur, err := s.UpdateRole(ctx, tm.ws, a, sampler.ID, v1.RoleUpdate{Name: &newName}, 7); !errors.Is(err, store.ErrStale) || cur.Version != 1 {
		t.Fatalf("stale update: %v %+v", err, cur)
	}
	if _, err := s.UpdateRole(ctx, tm.ws, a, sampler.ID, v1.RoleUpdate{Permissions: map[string]perm.Level{perm.EditRows: perm.All, perm.ManagePlugins: perm.All}}, 1); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("grant what one lacks: %v", err)
	}
	pos := 40
	if _, err := s.UpdateRole(ctx, tm.ws, a, sampler.ID, v1.RoleUpdate{Position: &pos}, 1); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("move a role above oneself: %v", err)
	}
	before = tm.versions(t)
	up, err := s.UpdateRole(ctx, tm.ws, a, sampler.ID, v1.RoleUpdate{Name: &newName, Permissions: map[string]perm.Level{perm.EditRows: perm.All, perm.ExportData: perm.All}}, 1)
	if err != nil || up.Name != newName || up.Version != 2 || up.Permissions[perm.ExportData] != perm.All {
		t.Fatalf("update: %v %+v", err, up)
	}
	if got := tm.bumped(t, before); len(got) != 0 {
		// b already exported through the base role, so nothing anyone may do changed.
		t.Fatalf("access versions bumped for %v, want none", got)
	}
	if _, err := s.UpdateRole(ctx, tm.ws, l, perm.Base, v1.RoleUpdate{Name: &newName}, 1); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("rename the base role: %v", err)
	}
	// The base role binds people above the deputy too: only the leader changes it.
	if _, err := s.UpdateRole(ctx, tm.ws, a, perm.Base, v1.RoleUpdate{Permissions: map[string]perm.Level{}}, 1); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("the deputy changes the base role: %v", err)
	}
	before = tm.versions(t)
	basePerms := copyLevels(v1.DefaultBasePermissions)
	delete(basePerms, perm.SendMessages)
	if _, err := s.UpdateRole(ctx, tm.ws, l, perm.Base, v1.RoleUpdate{Permissions: basePerms}, 1); err != nil {
		t.Fatal(err)
	}
	if got := tm.bumped(t, before); !got[a] || !got[b] || got[l] {
		t.Fatalf("access versions bumped for %v, want a and b (the leader holds everything anyway)", got)
	}
	if w, _ := s.Access(ctx, tm.ws); w.Has(b, perm.SendMessages) {
		t.Fatal("the base role change did not reach the rules")
	}

	if err := s.DeleteRole(ctx, tm.ws, l, perm.Base); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("delete the base role: %v", err)
	}
	if err := s.DeleteRole(ctx, tm.ws, a, deputy.ID); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("delete one's own place: %v", err)
	}
	before = tm.versions(t)
	if err := s.DeleteRole(ctx, tm.ws, a, sampler.ID); err != nil {
		t.Fatal(err)
	}
	if m, _ := tm.s.Members(ctx, tm.ws); len(m[2].Roles) != 0 {
		t.Fatalf("the deleted role stays on b: %+v", m[2])
	}
	if got := tm.bumped(t, before); !got[b] || len(got) != 1 {
		t.Fatalf("access versions bumped for %v, want only b", got)
	}
	if err := s.DeleteRole(ctx, tm.ws, l, sampler.ID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("delete twice: %v", err)
	}
	log, _ := s.Audit(ctx, tm.ws, l, 0, 100)
	actions := map[string]int{}
	for _, e := range log {
		actions[e.Action]++
	}
	if actions[v1.AuditCreateRole] != 2 || actions[v1.AuditUpdateRole] != 2 || actions[v1.AuditDeleteRole] != 1 || actions[v1.AuditSetRoles] != 2 {
		t.Fatalf("audit actions %v", actions)
	}
}

func copyLevels(in map[string]perm.Level) map[string]perm.Level {
	out := map[string]perm.Level{}
	for p, l := range in {
		out[p] = l
	}
	return out
}

const (
	layerA = "4f0e3c1a-7b2d-4e8f-9a10-1b2c3d4e5f60"
	layerB = "5a1f4d2b-8c3e-4f90-8b21-2c3d4e5f6071"
)

func geoPoints() v1.LayerInput {
	order := 2.0
	return v1.LayerInput{
		ID: layerA, Name: " 地质点 ", GeometryType: "Point", CRS: "EPSG:4490",
		Fields: []v1.FieldDef{
			{ID: "f1", Name: "岩性", Type: "string", Options: json.RawMessage(`["花岗岩", "灰岩"]`)},
			{ID: "f2", Name: "层号", Type: "integer"},
		},
		Style: json.RawMessage(`{"color": "#c00"}`), Order: &order,
	}
}

func layers(t *testing.T, open Opener) {
	ctx := context.Background()
	tm := newTeam(t, open(t, clock()))
	s, l, a := tm.s, tm.lead.Member.ID, tm.a.Member.ID
	if _, err := s.CreateLayer(ctx, tm.ws, a, geoPoints()); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a member creates a layer: %v", err)
	}
	for i, change := range []func(*v1.LayerInput){
		func(in *v1.LayerInput) { in.ID = "not-a-uuid" },
		func(in *v1.LayerInput) { in.ID = strings.ToUpper(layerA) },
		func(in *v1.LayerInput) { in.Name = "" },
		func(in *v1.LayerInput) { in.GeometryType = "Circle" },
		func(in *v1.LayerInput) { in.CRS = "EPSG:abc" },
		func(in *v1.LayerInput) { in.CRS = " " },
		func(in *v1.LayerInput) { in.Fields[0].ID = "geom" },
		func(in *v1.LayerInput) { in.Fields[1].ID = "f1" },
		func(in *v1.LayerInput) { in.Fields[0].Type = "float" },
		func(in *v1.LayerInput) { in.Fields[0].Options = json.RawMessage(`"花岗岩"`) },
		func(in *v1.LayerInput) { in.Style = json.RawMessage(`{`) },
	} {
		in := geoPoints()
		change(&in)
		if _, err := s.CreateLayer(ctx, tm.ws, l, in); !errors.Is(err, store.ErrInvalid) {
			t.Fatalf("bad layer %d: %v", i, err)
		}
	}
	lay, err := s.CreateLayer(ctx, tm.ws, l, geoPoints())
	if err != nil {
		t.Fatal(err)
	}
	if lay.SchemaVersion != 1 || lay.Seq == 0 || lay.Deleted != nil || lay.CreatedBy != l || len(lay.Fields) != 2 ||
		string(lay.Props[v1.PropName].Value) != `"地质点"` || lay.Props[v1.PropName].By != l || lay.Props[v1.PropName].Seq != lay.Seq ||
		string(lay.Props[v1.PropStyle].Value) != `{"color":"#c00"}` || string(lay.Props[v1.PropOrder].Value) != `2` {
		t.Fatalf("created %+v", lay)
	}
	if again, err := s.CreateLayer(ctx, tm.ws, l, geoPoints()); err != nil || again.Seq != lay.Seq {
		t.Fatalf("a retry returns the layer as it is: %v %+v", err, again)
	}
	other := geoPoints()
	other.Fields = other.Fields[:1]
	if cur, err := s.CreateLayer(ctx, tm.ws, l, other); !errors.Is(err, store.ErrStale) || len(cur.Fields) != 2 {
		t.Fatalf("the same id with another schema: %v %+v", err, cur)
	}
	if got, err := s.Layer(ctx, tm.ws, layerA); err != nil || got.Seq != lay.Seq || len(got.Fields) != 2 || string(got.Fields[0].Options) != `["花岗岩","灰岩"]` {
		t.Fatalf("read back: %v %+v", err, got)
	}
	if _, err := s.Layer(ctx, tm.ws, layerB); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("an unknown layer: %v", err)
	}

	// Schema changes: online, by version, all or nothing.
	if _, err := s.ChangeSchema(ctx, tm.ws, a, layerA, 1, []v1.SchemaOp{{Op: "rename_field", ID: "f1", Name: "x"}}); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a member without edit_schema: %v", err)
	}
	if cur, err := s.ChangeSchema(ctx, tm.ws, l, layerA, 3, []v1.SchemaOp{{Op: "rename_field", ID: "f1", Name: "x"}}); !errors.Is(err, store.ErrStale) || cur.SchemaVersion != 1 {
		t.Fatalf("a stale change: %v %+v", err, cur)
	}
	changed, err := s.ChangeSchema(ctx, tm.ws, l, layerA, 1, []v1.SchemaOp{
		{Op: "add_field", Field: &v1.FieldDef{ID: "f3", Name: "采样编号", Type: "string"}},
		{Op: "rename_field", ID: "f1", Name: " 岩石名称 "},
		{Op: "set_options", ID: "f1", Options: json.RawMessage(`["花岗岩","灰岩","砂岩"]`)},
		{Op: "delete_field", ID: "f2"},
	})
	if err != nil || changed.SchemaVersion != 2 || changed.Seq <= lay.Seq || len(changed.Fields) != 2 ||
		changed.Fields[0].Name != "岩石名称" || string(changed.Fields[0].Options) != `["花岗岩","灰岩","砂岩"]` || changed.Fields[1].ID != "f3" {
		t.Fatalf("change: %v %+v", err, changed)
	}
	for i, ops := range [][]v1.SchemaOp{
		nil,
		{{Op: "drop_table"}},
		{{Op: "rename_field", ID: "nope", Name: "x"}},
		{{Op: "add_field", Field: &v1.FieldDef{ID: "f4", Name: "x", Type: "string"}}, {Op: "add_field", Field: &v1.FieldDef{ID: "f2", Name: "x", Type: "string"}}},
	} {
		if _, err := s.ChangeSchema(ctx, tm.ws, l, layerA, 2, ops); !errors.Is(err, store.ErrInvalid) {
			t.Fatalf("bad ops %d: %v", i, err)
		}
	}
	if got, _ := s.Layer(ctx, tm.ws, layerA); got.SchemaVersion != 2 || len(got.Fields) != 2 {
		t.Fatalf("a refused change left a trace (f4, or f2 back): %+v", got)
	}

	// Deleting keeps the layer as a tombstone, which can come back.
	if _, err := s.DeleteLayer(ctx, tm.ws, a, layerA); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a member deletes: %v", err)
	}
	gone, err := s.DeleteLayer(ctx, tm.ws, l, layerA)
	if err != nil || gone.Deleted == nil || string(gone.Deleted.Value) != "true" || gone.Seq <= changed.Seq {
		t.Fatalf("delete: %v %+v", err, gone)
	}
	if again, err := s.DeleteLayer(ctx, tm.ws, l, layerA); err != nil || again.Seq != gone.Seq {
		t.Fatalf("delete twice: %v %+v", err, again)
	}
	if _, err := s.ChangeSchema(ctx, tm.ws, l, layerA, 2, []v1.SchemaOp{{Op: "delete_field", ID: "f3"}}); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("change a deleted layer: %v", err)
	}
	back, err := s.RestoreLayer(ctx, tm.ws, l, layerA)
	if err != nil || string(back.Deleted.Value) != "false" || back.Seq <= gone.Seq {
		t.Fatalf("restore: %v %+v", err, back)
	}
	all, err := s.Layers(ctx, tm.ws)
	if err != nil || len(all) != 1 || all[0].Seq != back.Seq {
		t.Fatalf("layers: %v %+v", err, all)
	}

	yes := true
	if _, err := s.UpdateWorkspace(ctx, tm.ws, l, v1.WorkspaceUpdate{Archived: &yes}); err != nil {
		t.Fatal(err)
	}
	in := geoPoints()
	in.ID = layerB
	if _, err := s.CreateLayer(ctx, tm.ws, l, in); !errors.Is(err, store.ErrArchived) {
		t.Fatalf("create in an archived workspace: %v", err)
	}
}

func overrides(t *testing.T, open Opener) {
	ctx := context.Background()
	tm := newTeam(t, open(t, clock()))
	s, l, a, b := tm.s, tm.lead.Member.ID, tm.a.Member.ID, tm.b.Member.ID
	if _, err := s.CreateLayer(ctx, tm.ws, l, geoPoints()); err != nil {
		t.Fatal(err)
	}
	guest, err := s.CreateRole(ctx, tm.ws, l, "访客", 5, nil)
	if err != nil {
		t.Fatal(err)
	}
	mapper, err := s.CreateRole(ctx, tm.ws, l, "制图", 10, map[string]perm.Level{perm.EditSchema: perm.All})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetMemberRoles(ctx, tm.ws, l, b, []string{guest.ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetMemberRoles(ctx, tm.ws, l, a, []string{mapper.ID}); err != nil {
		t.Fatal(err)
	}

	hide := map[string]perm.Level{perm.ViewLayer: perm.None}
	if err := s.SetLayerOverride(ctx, tm.ws, a, layerA, guest.ID, hide); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("a member without manage_roles: %v", err)
	}
	for _, bad := range []map[string]perm.Level{{perm.ExportData: perm.None}, {perm.ViewLayer: perm.Own}} {
		if err := s.SetLayerOverride(ctx, tm.ws, l, layerA, guest.ID, bad); !errors.Is(err, store.ErrInvalid) {
			t.Fatalf("override %v: %v", bad, err)
		}
	}
	if err := s.SetLayerOverride(ctx, tm.ws, l, layerB, guest.ID, hide); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("an unknown layer: %v", err)
	}
	if err := s.SetLayerOverride(ctx, tm.ws, l, layerA, "nope", hide); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("an unknown role: %v", err)
	}

	// Hidden from everyone through the base role...
	before := tm.versions(t)
	if err := s.SetLayerOverride(ctx, tm.ws, l, layerA, perm.Base, hide); err != nil {
		t.Fatal(err)
	}
	if got := tm.bumped(t, before); !got[a] || !got[b] || got[l] {
		t.Fatalf("access versions bumped for %v, want a and b", got)
	}
	w, _ := s.Access(ctx, tm.ws)
	if w.Level(a, perm.ViewLayer, layerA) != perm.None || w.Level(l, perm.ViewLayer, layerA) != perm.All {
		t.Fatal("the base override does not reach the rules")
	}
	// ...so the mapper cannot change a schema they cannot see.
	if _, err := s.ChangeSchema(ctx, tm.ws, a, layerA, 1, []v1.SchemaOp{{Op: "rename_field", ID: "f1", Name: "x"}}); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("change a hidden layer's schema: %v", err)
	}
	// A role's own override brings it back for that role only.
	before = tm.versions(t)
	if err := s.SetLayerOverride(ctx, tm.ws, l, layerA, guest.ID, map[string]perm.Level{perm.ViewLayer: perm.All, perm.EditRows: perm.None}); err != nil {
		t.Fatal(err)
	}
	if got := tm.bumped(t, before); !got[b] || len(got) != 1 {
		t.Fatalf("access versions bumped for %v, want only b", got)
	}
	got, err := s.LayerOverrides(ctx, tm.ws, layerA)
	if err != nil || got[perm.Base][perm.ViewLayer] != perm.None || got[guest.ID][perm.ViewLayer] != perm.All || got[guest.ID][perm.EditRows] != perm.None {
		t.Fatalf("overrides: %v %+v", err, got)
	}
	// An empty set removes the role's override.
	if err := s.SetLayerOverride(ctx, tm.ws, l, layerA, guest.ID, nil); err != nil {
		t.Fatal(err)
	}
	if got, _ := s.LayerOverrides(ctx, tm.ws, layerA); len(got) != 1 {
		t.Fatalf("after removing: %+v", got)
	}
	// Deleting a role deletes its overrides.
	if err := s.SetLayerOverride(ctx, tm.ws, l, layerA, guest.ID, hide); err != nil {
		t.Fatal(err)
	}
	if err := s.DeleteRole(ctx, tm.ws, l, guest.ID); err != nil {
		t.Fatal(err)
	}
	if got, _ := s.LayerOverrides(ctx, tm.ws, layerA); got[guest.ID] != nil {
		t.Fatalf("a deleted role's override stays: %+v", got)
	}
	if _, err := s.LayerOverrides(ctx, tm.ws, layerB); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("overrides of an unknown layer: %v", err)
	}
	log, _ := s.Audit(ctx, tm.ws, l, 0, 100)
	n := 0
	for _, e := range log {
		if e.Action == v1.AuditSetOverride && e.Target == layerA {
			n++
		}
	}
	if n != 4 {
		t.Fatalf("%d override entries in the audit log, want 4", n)
	}
}

// uuid makes the n-th test id, a lowercase UUID as clients write them.
func uuid(n int) string { return fmt.Sprintf("00000000-0000-4000-8000-%012d", n) }

func edit(value string, at time.Time) merge.Edit {
	return merge.Edit{Value: json.RawMessage(value), EditedAt: at}
}

// pushOne pushes one change and returns its result, failing on a request error.
func pushOne(t *testing.T, s v1.Store, ws, actor string, now time.Time, c v1.Change) v1.Result {
	t.Helper()
	rs, _, err := s.Push(context.Background(), ws, actor, now, []v1.Change{c})
	if err != nil || len(rs) != 1 {
		t.Fatalf("push %+v: %v %+v", c, err, rs)
	}
	return rs[0]
}

func pushRows(t *testing.T, open Opener) {
	ctx := context.Background()
	c := newClock()
	tm := newTeam(t, open(t, c.Now))
	s, l, a, b := tm.s, tm.lead.Member.ID, tm.a.Member.ID, tm.b.Member.ID
	in := geoPoints()
	in.Fields = append(in.Fields, v1.FieldDef{ID: "f3", Name: "日期", Type: "date"})
	if _, err := s.CreateLayer(ctx, tm.ws, l, in); err != nil {
		t.Fatal(err)
	}
	row := uuid(1)
	t0 := time.Date(2026, 11, 26, 7, 0, 0, 0, time.UTC)
	point := `{"type":"Point","coordinates":[114.0,30.0]}`

	// A new row: every field taken, its pusher owns it.
	first := v1.Change{ID: uuid(100), Layer: layerA, Row: row, Set: map[string]merge.Edit{"f1": edit(`"D001"`, t0), "geom": edit(point, t0)}}
	res := pushOne(t, s, tm.ws, a, t0, first)
	if res.Status != v1.Applied || res.Seq == 0 || len(res.Conflicts) != 0 {
		t.Fatalf("new row: %+v", res)
	}
	got, cs, err := s.Row(ctx, tm.ws, row)
	if err != nil || got.Owner != a || got.Layer != layerA || got.Seq != res.Seq || got.Conflicted || len(cs) != 0 ||
		string(got.Fields["f1"].Value) != `"D001"` || got.Fields["f1"].By != a || got.Fields["geom"].Seq != res.Seq {
		t.Fatalf("stored: %v %+v %+v", err, got, cs)
	}
	if again := pushOne(t, s, tm.ws, a, t0, first); again.Status != v1.Duplicate || again.Seq != res.Seq {
		t.Fatalf("a retry: %+v", again)
	}

	// Edit times move to the server's clock: a phone 1 h fast that edited 10 min before sending.
	fast := c.Now().Add(time.Hour)
	at := pushOne(t, s, tm.ws, a, fast, v1.Change{ID: uuid(101), Layer: layerA, Row: row, BaseSeq: res.Seq,
		Set: map[string]merge.Edit{"f3": edit(`"2026-11-26"`, fast.Add(-10*time.Minute))}})
	got, _, _ = s.Row(ctx, tm.ws, row)
	if want := c.at.Add(-10 * time.Minute); at.Status != v1.Applied || !got.Fields["f3"].At.Equal(want) {
		t.Fatalf("edit time %v, want %v (%+v)", got.Fields["f3"].At, want, at)
	}

	// Rejections leave no trace.
	for i, bad := range []struct {
		c    v1.Change
		code string
	}{
		{v1.Change{ID: "c1", Layer: layerA, Row: row, Set: map[string]merge.Edit{"f1": edit(`"x"`, t0)}}, v1.RejectInvalidValue},
		{v1.Change{ID: uuid(102), Layer: layerA, Row: row}, v1.RejectInvalidValue},
		{v1.Change{ID: uuid(103), Layer: layerA, Row: row, Set: map[string]merge.Edit{"geom": edit(`{"type":"LineString","coordinates":[[1,2],[3,4]]}`, t0)}}, v1.RejectInvalidValue},
		{v1.Change{ID: uuid(104), Layer: layerA, Row: row, Set: map[string]merge.Edit{"geom": edit(`{"type":"Point","coordinates":[1]}`, t0)}}, v1.RejectInvalidValue},
		{v1.Change{ID: uuid(105), Layer: layerA, Row: row, Set: map[string]merge.Edit{"f2": edit(`"three"`, t0)}}, v1.RejectInvalidValue},
		{v1.Change{ID: uuid(106), Layer: layerA, Row: row, Set: map[string]merge.Edit{"f2": edit(`9007199254740993`, t0)}}, v1.RejectInvalidValue},
		{v1.Change{ID: uuid(107), Layer: layerA, Row: row, Set: map[string]merge.Edit{"f3": edit(`"2026-13-40"`, t0)}}, v1.RejectInvalidValue},
		{v1.Change{ID: uuid(108), Layer: layerA, Row: row, Delete: &merge.Edit{Value: json.RawMessage(`"yes"`), EditedAt: t0}}, v1.RejectInvalidValue},
		{v1.Change{ID: uuid(109), Layer: layerB, Row: row, Set: map[string]merge.Edit{"f1": edit(`"x"`, t0)}}, v1.RejectUnknownLayer},
	} {
		if r := pushOne(t, s, tm.ws, a, t0, bad.c); r.Status != v1.Rejected || r.Error != bad.code || r.Message == "" {
			t.Fatalf("bad change %d: %+v, want %s", i, r, bad.code)
		}
	}
	if after, _, _ := s.Row(ctx, tm.ws, row); after.Seq != at.Seq {
		t.Fatalf("a rejected change moved the row to seq %d", after.Seq)
	}
	if _, _, err := s.Push(ctx, tm.ws, a, t0, []v1.Change{
		{ID: uuid(110), Layer: layerA, Row: row, Set: map[string]merge.Edit{"f1": edit(`"x"`, t0)}},
		{ID: uuid(111), Layer: layerA, Row: row, Set: map[string]merge.Edit{"f1": edit(`"y"`, t0)}},
	}); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("one row twice in a push: %v", err)
	}

	// Two members edit offline from the same base.
	base := at.Seq
	r1 := pushOne(t, s, tm.ws, a, t0, v1.Change{ID: uuid(112), Layer: layerA, Row: row, BaseSeq: base,
		Set: map[string]merge.Edit{"f1": edit(`"from a"`, t0.Add(time.Minute))}})
	r2 := pushOne(t, s, tm.ws, b, t0, v1.Change{ID: uuid(113), Layer: layerA, Row: row, BaseSeq: base,
		Set: map[string]merge.Edit{"f1": edit(`"from b"`, t0.Add(2*time.Minute)), "f2": edit(`7`, t0)}})
	if r1.Status != v1.Applied || r2.Status != v1.Applied || len(r2.Conflicts) != 1 {
		t.Fatalf("concurrent edits: %+v %+v", r1, r2)
	}
	k := r2.Conflicts[0]
	if k.Kind != merge.KindValue || k.Field != "f1" || k.Row != row || k.Layer != layerA || k.Seq != r2.Seq ||
		string(k.Kept.Value) != `"from b"` || string(k.Lost.Value) != `"from a"` || k.Lost.By != a {
		t.Fatalf("conflict: %+v", k)
	}
	got, cs, _ = s.Row(ctx, tm.ws, row)
	if !got.Conflicted || len(cs) != 1 || cs[0].ID != k.ID || string(got.Fields["f2"].Value) != "7" {
		t.Fatalf("after the conflict: %+v %+v", got, cs)
	}

	// A field deleted from the layer keeps the value as a conflict.
	if _, err := s.ChangeSchema(ctx, tm.ws, l, layerA, 1, []v1.SchemaOp{{Op: "delete_field", ID: "f2"}}); err != nil {
		t.Fatal(err)
	}
	gone := pushOne(t, s, tm.ws, a, t0, v1.Change{ID: uuid(114), Layer: layerA, Row: row, BaseSeq: r2.Seq,
		Set: map[string]merge.Edit{"f2": edit(`8`, t0), "f1": edit(`"again"`, t0.Add(time.Hour))}})
	if gone.Status != v1.Applied || len(gone.Conflicts) != 1 || gone.Conflicts[0].Kind != merge.KindDeletedField {
		t.Fatalf("an edit to a deleted field: %+v", gone)
	}

	// A delete loses to an edit made meanwhile.
	edited := pushOne(t, s, tm.ws, b, t0, v1.Change{ID: uuid(115), Layer: layerA, Row: row, BaseSeq: gone.Seq,
		Set: map[string]merge.Edit{"f3": edit(`"2026-11-27"`, t0.Add(time.Hour))}})
	del := pushOne(t, s, tm.ws, a, t0, v1.Change{ID: uuid(116), Layer: layerA, Row: row, BaseSeq: gone.Seq,
		Delete: &merge.Edit{Value: json.RawMessage("true"), EditedAt: t0.Add(time.Hour)}})
	if edited.Status != v1.Applied || del.Status != v1.Applied || len(del.Conflicts) != 1 || del.Conflicts[0].Kind != merge.KindDeleteRejected {
		t.Fatalf("delete after an edit: %+v %+v", edited, del)
	}

	// Rows of someone else need "all"; deleting needs delete_rows.
	own := map[string]perm.Level{}
	for p, lv := range v1.DefaultBasePermissions {
		own[p] = lv
	}
	own[perm.EditRows], own[perm.DeleteRows] = perm.Own, perm.Own
	if _, err := s.UpdateRole(ctx, tm.ws, l, perm.Base, v1.RoleUpdate{Permissions: own}, 1); err != nil {
		t.Fatal(err)
	}
	if r := pushOne(t, s, tm.ws, b, t0, v1.Change{ID: uuid(117), Layer: layerA, Row: row, BaseSeq: del.Seq,
		Set: map[string]merge.Edit{"f1": edit(`"b's"`, t0)}}); r.Error != v1.RejectForbidden {
		t.Fatalf("edit someone else's row with own: %+v", r)
	}
	if r := pushOne(t, s, tm.ws, b, t0, v1.Change{ID: uuid(118), Layer: layerA, Row: row, BaseSeq: del.Seq,
		Delete: &merge.Edit{Value: json.RawMessage("true"), EditedAt: t0}}); r.Error != v1.RejectForbidden {
		t.Fatalf("delete someone else's row with own: %+v", r)
	}
	if r := pushOne(t, s, tm.ws, b, t0, v1.Change{ID: uuid(119), Layer: layerA, Row: uuid(2),
		Set: map[string]merge.Edit{"f1": edit(`"b's"`, t0)}}); r.Status != v1.Applied {
		t.Fatalf("a new row of one's own: %+v", r)
	}
	if r := pushOne(t, s, tm.ws, l, t0, v1.Change{ID: uuid(120), Layer: layerA, Row: uuid(2), BaseSeq: 0,
		Delete: &merge.Edit{Value: json.RawMessage("true"), EditedAt: t0.Add(2 * time.Hour)}}); r.Status != v1.Applied {
		t.Fatalf("the leader deletes anything: %+v", r)
	}

	// A row id stays in its layer.
	other := geoPoints()
	other.ID = layerB
	if _, err := s.CreateLayer(ctx, tm.ws, l, other); err != nil {
		t.Fatal(err)
	}
	if r := pushOne(t, s, tm.ws, l, t0, v1.Change{ID: uuid(121), Layer: layerB, Row: row, Set: map[string]merge.Edit{"f1": edit(`"x"`, t0)}}); r.Error != v1.RejectInvalidValue {
		t.Fatalf("a row moved to another layer: %+v", r)
	}

	// Several changes in one push get rising seqs; the reply gives the latest.
	rs, last, err := s.Push(ctx, tm.ws, l, t0, []v1.Change{
		{ID: uuid(122), Layer: layerB, Row: uuid(3), Set: map[string]merge.Edit{"f1": edit(`"p"`, t0)}},
		{ID: uuid(123), Layer: layerB, Row: uuid(4), Set: map[string]merge.Edit{"f1": edit(`"q"`, t0)}},
	})
	if err != nil || len(rs) != 2 || rs[1].Seq != rs[0].Seq+1 || last != rs[1].Seq {
		t.Fatalf("batch: %v %+v %d", err, rs, last)
	}

	yes := true
	if _, err := s.UpdateWorkspace(ctx, tm.ws, l, v1.WorkspaceUpdate{Archived: &yes}); err != nil {
		t.Fatal(err)
	}
	if r := pushOne(t, s, tm.ws, l, t0, v1.Change{ID: uuid(124), Layer: layerB, Row: uuid(5), Set: map[string]merge.Edit{"f1": edit(`"x"`, t0)}}); r.Error != v1.RejectArchived {
		t.Fatalf("push to an archived workspace: %+v", r)
	}
}

func pushLayerProps(t *testing.T, open Opener) {
	ctx := context.Background()
	tm := newTeam(t, open(t, clock()))
	s, l, a := tm.s, tm.lead.Member.ID, tm.a.Member.ID
	lay, err := s.CreateLayer(ctx, tm.ws, l, geoPoints())
	if err != nil {
		t.Fatal(err)
	}
	t0 := time.Date(2026, 11, 26, 7, 0, 0, 0, time.UTC)
	if r := pushOne(t, s, tm.ws, a, t0, v1.Change{ID: uuid(200), Layer: v1.TableLayers, Row: layerA, BaseSeq: lay.Seq,
		Set: map[string]merge.Edit{v1.PropName: edit(`"新名"`, t0)}}); r.Error != v1.RejectForbidden {
		t.Fatalf("a member without edit_schema renames: %+v", r)
	}
	for i, set := range []map[string]merge.Edit{
		{v1.PropName: edit(`""`, t0)},
		{v1.PropStyle: edit(`[1]`, t0)},
		{v1.PropOrder: edit(`"first"`, t0)},
		{"colour": edit(`"red"`, t0)},
	} {
		if r := pushOne(t, s, tm.ws, l, t0, v1.Change{ID: uuid(201 + i), Layer: v1.TableLayers, Row: layerA, BaseSeq: lay.Seq, Set: set}); r.Error != v1.RejectInvalidValue {
			t.Fatalf("bad property %d: %+v", i, r)
		}
	}
	if r := pushOne(t, s, tm.ws, l, t0, v1.Change{ID: uuid(210), Layer: v1.TableLayers, Row: layerA, BaseSeq: lay.Seq,
		Delete: &merge.Edit{Value: json.RawMessage("true"), EditedAt: t0}}); r.Error != v1.RejectInvalidValue {
		t.Fatalf("delete a layer through a change: %+v", r)
	}
	r := pushOne(t, s, tm.ws, l, t0, v1.Change{ID: uuid(211), Layer: v1.TableLayers, Row: layerA, BaseSeq: lay.Seq,
		Set: map[string]merge.Edit{v1.PropName: edit(`"新名"`, t0), v1.PropOrder: edit(`5`, t0)}})
	got, _ := s.Layer(ctx, tm.ws, layerA)
	if r.Status != v1.Applied || got.Seq != r.Seq || string(got.Props[v1.PropName].Value) != `"新名"` || string(got.Props[v1.PropOrder].Value) != `5` ||
		string(got.Props[v1.PropStyle].Value) != `{"color":"#c00"}` || got.SchemaVersion != 1 {
		t.Fatalf("layer properties: %+v %+v", r, got)
	}
	// Two offline renames: the later edit wins, the other is kept as a conflict.
	r2 := pushOne(t, s, tm.ws, l, t0, v1.Change{ID: uuid(212), Layer: v1.TableLayers, Row: layerA, BaseSeq: lay.Seq,
		Set: map[string]merge.Edit{v1.PropName: edit(`"更早的名字"`, t0.Add(-time.Hour))}})
	if len(r2.Conflicts) != 1 || r2.Conflicts[0].Layer != v1.TableLayers || r2.Conflicts[0].Row != layerA || string(r2.Conflicts[0].Lost.Value) != `"更早的名字"` {
		t.Fatalf("concurrent renames: %+v", r2)
	}
	if r := pushOne(t, s, tm.ws, l, t0, v1.Change{ID: uuid(213), Layer: v1.TableLayers, Row: layerB,
		Set: map[string]merge.Edit{v1.PropName: edit(`"x"`, t0)}}); r.Error != v1.RejectUnknownLayer {
		t.Fatalf("an unknown layer: %+v", r)
	}
}

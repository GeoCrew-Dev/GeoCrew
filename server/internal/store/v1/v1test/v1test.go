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
		{"UpdateWorkspace", updateWorkspace},
		{"RemoveAndLeave", removeAndLeave},
		{"TransferLeader", transferLeader},
		{"Devices", devices},
		{"TransferCode", transferCode},
		{"TransferCodeRace", transferCodeRace},
		{"AuditLog", auditLog},
		{"Roles", roles},
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
	if got := tm.bumped(t, before); got[b] != false || len(got) != 0 {
		// b already exported through the base role, so nothing b may do changed.
		t.Fatalf("access versions bumped for %v, want none", got)
	}
	if _, err := s.UpdateRole(ctx, tm.ws, l, perm.Base, v1.RoleUpdate{Name: &newName}, 1); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("rename the base role: %v", err)
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

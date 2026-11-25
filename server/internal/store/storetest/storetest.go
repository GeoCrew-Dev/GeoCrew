// Package storetest is the behaviour every store.Store backend must have.
// A backend's tests call [Run] with a function that opens an empty store.
package storetest

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
)

// Opener returns a new, empty store that is closed when the test ends.
type Opener func(t *testing.T) store.Store

// Run checks a backend against the store contract.
func Run(t *testing.T, open Opener) {
	for _, c := range []struct {
		name string
		fn   func(*testing.T, Opener)
	}{
		{"WorkspaceLifecycle", workspaceLifecycle},
		{"PushPullConflict", pushPullConflict},
		{"PushTargets", pushTargets},
		{"FileMeta", fileMeta},
		{"Settings", settings},
		{"Comments", comments},
		{"ViewLinks", viewLinks},
		{"ConcurrentPushes", concurrentPushes},
		{"LockedProps", lockedProps},
		{"Outings", outings},
		{"DeleteWorkspace", deleteWorkspace},
		{"Archive", archive},
	} {
		t.Run(c.name, func(t *testing.T) { c.fn(t, open) })
	}
}

func workspaceLifecycle(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	ws, leader, ltok, err := d.CreateWorkspace(ctx, " 野外一组 ", "组长")
	if err != nil {
		t.Fatal(err)
	}
	if ws.Name != "野外一组" || leader.Role != store.RoleLeader || len(ws.InviteCode) != 8 {
		t.Fatalf("unexpected workspace %+v leader %+v", ws, leader)
	}
	if _, _, _, err := d.CreateWorkspace(ctx, "", "x"); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("empty name: %v", err)
	}

	_, m, mtok, err := d.Join(ctx, " "+ws.InviteCode+" ", "组员")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := d.Join(ctx, "NOPE", "x"); !errors.Is(err, store.ErrBadInvite) {
		t.Fatalf("bad invite: %v", err)
	}

	if got, err := d.Authenticate(ctx, ws.ID, mtok); err != nil || got.ID != m.ID {
		t.Fatalf("auth member: %v %+v", err, got)
	}
	if _, err := d.Authenticate(ctx, "other", ltok); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("auth other workspace: %v", err)
	}

	// The only leader cannot step down or be revoked.
	if err := d.SetRole(ctx, ws.ID, leader.ID, store.RoleMember); !errors.Is(err, store.ErrLastLeader) {
		t.Fatalf("demote last leader: %v", err)
	}
	if err := d.SetRole(ctx, ws.ID, m.ID, store.RoleLeader); err != nil {
		t.Fatal(err)
	}
	if err := d.SetRole(ctx, ws.ID, leader.ID, store.RoleReadonly); err != nil {
		t.Fatalf("demote with second leader: %v", err)
	}

	if err := d.RevokeMember(ctx, ws.ID, leader.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := d.Authenticate(ctx, ws.ID, ltok); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("revoked token still works: %v", err)
	}

	old := ws.InviteCode
	code, err := d.ResetInvite(ctx, ws.ID)
	if err != nil || code == old {
		t.Fatalf("reset invite: %v %q", err, code)
	}
	if _, _, _, err := d.Join(ctx, old, "late"); !errors.Is(err, store.ErrBadInvite) {
		t.Fatalf("old invite still works: %v", err)
	}
	ms, err := d.Members(ctx, ws.ID)
	if err != nil || len(ms) != 2 {
		t.Fatalf("members: %v %d", err, len(ms))
	}
}

func feat(id string, v int64, at time.Time) store.Feature {
	return store.Feature{
		ID: id, Version: v, UpdatedAt: at,
		Geometry:   []byte(`{"type":"Point","coordinates":[116.39,39.91]}`),
		Properties: []byte(`{"kind":"point"}`),
	}
}

func pushPullConflict(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	ws, a, _, _ := d.CreateWorkspace(ctx, "w", "a")
	_, b, _, _ := d.Join(ctx, ws.InviteCode, "b")
	t0 := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)

	res, seq, err := d.PushFeatures(ctx, ws.ID, a.ID, []store.Feature{feat("f1", 1, t0), feat("f2", 1, t0)})
	if err != nil || seq != 2 || res[0].Status != store.PushApplied || res[1].Seq != 2 {
		t.Fatalf("first push: %v seq=%d %+v", err, seq, res)
	}

	// Retry of the same revision is idempotent.
	res, seq, _ = d.PushFeatures(ctx, ws.ID, a.ID, []store.Feature{feat("f1", 1, t0)})
	if res[0].Status != store.PushDuplicate || seq != 2 {
		t.Fatalf("retry: seq=%d %+v", seq, res)
	}

	// b edits f1 to version 2; a's concurrent version-2 edit is earlier, so it is stale.
	res, _, _ = d.PushFeatures(ctx, ws.ID, b.ID, []store.Feature{feat("f1", 2, t0.Add(time.Minute))})
	if res[0].Status != store.PushApplied || res[0].Seq != 3 {
		t.Fatalf("b edit: %+v", res)
	}
	res, seq, _ = d.PushFeatures(ctx, ws.ID, a.ID, []store.Feature{feat("f1", 2, t0.Add(30*time.Second))})
	if res[0].Status != store.PushStale || res[0].Current == nil || res[0].Current.UpdatedBy != b.ID || seq != 3 {
		t.Fatalf("stale: seq=%d %+v", seq, res)
	}

	// Soft delete.
	del := feat("f2", 2, t0.Add(time.Hour))
	del.Deleted = true
	if res, _, _ := d.PushFeatures(ctx, ws.ID, a.ID, []store.Feature{del}); res[0].Status != store.PushApplied {
		t.Fatalf("delete: %+v", res)
	}

	fs, more, err := d.PullFeatures(ctx, ws.ID, 0, 1)
	if err != nil || len(fs) != 1 || !more || fs[0].ID != "f1" || fs[0].Seq != 3 {
		t.Fatalf("pull page 1: %v more=%v %+v", err, more, fs)
	}
	fs, more, _ = d.PullFeatures(ctx, ws.ID, fs[0].Seq, 10)
	if len(fs) != 1 || more || !fs[0].Deleted {
		t.Fatalf("pull page 2: more=%v %+v", more, fs)
	}

	hist, err := d.FeatureHistory(ctx, ws.ID, "f1")
	if err != nil || len(hist) != 1 || hist[0].Version != 1 {
		t.Fatalf("history: %v %+v", err, hist)
	}
	w, _ := d.Workspace(ctx, ws.ID)
	if w.LastSeq != 4 {
		t.Fatalf("last seq: %d", w.LastSeq)
	}
}

func pushTargets(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	ws, leader, _, _ := d.CreateWorkspace(ctx, "组", "组长")
	_, m, _, _ := d.Join(ctx, ws.InviteCode, "组员")
	if _, err := d.PushTarget(ctx, ws.ID, m.ID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("none yet: %v", err)
	}
	for _, mem := range []string{leader.ID, m.ID} {
		if err := d.SetPushTarget(ctx, ws.ID, store.PushTarget{MemberID: mem, Endpoint: "https://a/" + mem, Events: []string{"task"}}); err != nil {
			t.Fatal(err)
		}
	}
	// Registering again replaces the endpoint.
	keys := &store.PushKeys{P256dh: "pub", Auth: "auth"}
	if err := d.SetPushTarget(ctx, ws.ID, store.PushTarget{MemberID: m.ID, Endpoint: "https://b/x", Events: []string{"message", "task"}, Keys: keys}); err != nil {
		t.Fatal(err)
	}
	got, err := d.PushTarget(ctx, ws.ID, m.ID)
	if err != nil || got.Endpoint != "https://b/x" || len(got.Events) != 2 || got.UpdatedAt.IsZero() || got.Keys == nil || *got.Keys != *keys {
		t.Fatalf("target: %+v %v", got, err)
	}
	if l, _ := d.PushTargets(ctx, ws.ID); len(l) != 2 {
		t.Fatalf("list: %+v", l)
	}
	// A revoked member's device stops getting notices.
	if err := d.RevokeMember(ctx, ws.ID, m.ID); err != nil {
		t.Fatal(err)
	}
	if l, _ := d.PushTargets(ctx, ws.ID); len(l) != 1 || l[0].MemberID != leader.ID {
		t.Fatalf("after revoke: %+v", l)
	}
	if err := d.DeletePushTarget(ctx, ws.ID, leader.ID); err != nil {
		t.Fatal(err)
	}
	if err := d.DeletePushTarget(ctx, ws.ID, leader.ID); err != nil {
		t.Fatalf("deleting twice: %v", err)
	}
	if l, _ := d.PushTargets(ctx, ws.ID); len(l) != 0 {
		t.Fatalf("after delete: %+v", l)
	}
}

func fileMeta(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	ws, m, _, _ := d.CreateWorkspace(ctx, "w", "a")
	meta := store.FileMeta{SHA256: "ab", Size: 5 << 30, ContentType: "image/jpeg", CreatedAt: time.Now(), CreatedBy: m.ID}
	if err := d.PutFileMeta(ctx, ws.ID, meta); err != nil {
		t.Fatal(err)
	}
	if err := d.PutFileMeta(ctx, ws.ID, meta); err != nil {
		t.Fatalf("duplicate meta: %v", err)
	}
	got, err := d.FileMeta(ctx, ws.ID, "ab")
	if err != nil || got.Size != 5<<30 || got.CreatedBy != m.ID {
		t.Fatalf("file meta: %v %+v", err, got)
	}
	if _, err := d.FileMeta(ctx, ws.ID, "cd"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("missing meta: %v", err)
	}
}

func settings(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	ws, _, _, _ := d.CreateWorkspace(ctx, "w", "a")
	raw, v, err := d.Settings(ctx, ws.ID)
	if err != nil || v != 0 || string(raw) != "{}" {
		t.Fatalf("new settings: %v %d %s", err, v, raw)
	}
	v, err = d.PutSettings(ctx, ws.ID, json.RawMessage(`{"fields":[]}`), 0)
	if err != nil || v != 1 {
		t.Fatalf("put: %v %d", err, v)
	}
	if _, err := d.PutSettings(ctx, ws.ID, json.RawMessage(`{}`), 0); !errors.Is(err, store.ErrStale) {
		t.Fatalf("stale put: %v", err)
	}
	if _, err := d.PutSettings(ctx, ws.ID, json.RawMessage(`[1]`), -1); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("not an object: %v", err)
	}
	if _, err := d.PutSettings(ctx, "nope", json.RawMessage(`{}`), -1); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("unknown workspace: %v", err)
	}
	if w, _ := d.Workspace(ctx, ws.ID); w.SettingsVersion != 1 {
		t.Fatalf("workspace version: %+v", w)
	}
}

func comments(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	ws, a, _, _ := d.CreateWorkspace(ctx, "w", "组长")
	_, b, _, _ := d.Join(ctx, ws.InviteCode, "组员")
	c1, err := d.AddComment(ctx, ws.ID, a.ID, store.Comment{ID: "c1", Body: "集合", At: []float64{116.4, 39.9}, Urgent: true})
	if err != nil || c1.Seq != 1 || c1.Nickname != "组长" || !c1.Urgent || len(c1.At) != 2 {
		t.Fatalf("add: %v %+v", err, c1)
	}
	again, err := d.AddComment(ctx, ws.ID, a.ID, store.Comment{ID: "c1", Body: "集合", At: []float64{116.4, 39.9}, Urgent: true})
	if err != nil || again.Seq != 1 {
		t.Fatalf("retry: %v %+v", err, again)
	}
	if _, err := d.AddComment(ctx, ws.ID, b.ID, store.Comment{ID: "c1", Body: "别的"}); !errors.Is(err, store.ErrConflict) {
		t.Fatalf("reused id: %v", err)
	}
	if _, err := d.AddComment(ctx, ws.ID, b.ID, store.Comment{ID: "c2", FeatureID: "f1", Body: "看这里"}); err != nil {
		t.Fatal(err)
	}
	if _, err := d.DeleteComment(ctx, ws.ID, "c1", b.ID, false); !errors.Is(err, store.ErrForbidden) {
		t.Fatalf("delete someone else's: %v", err)
	}
	del, err := d.DeleteComment(ctx, ws.ID, "c1", b.ID, true)
	if err != nil || !del.Deleted || del.Body != "" || del.Urgent || del.At != nil || del.Seq != 3 {
		t.Fatalf("leader delete: %v %+v", err, del)
	}
	call, err := d.AddComment(ctx, ws.ID, a.ID, store.Comment{ID: "r1", Body: "报平安", RollCall: true})
	if err != nil || !call.RollCall {
		t.Fatalf("roll call: %v %+v", err, call)
	}
	if _, err := d.AddComment(ctx, ws.ID, b.ID, store.Comment{ID: "a1", Body: "平安", AnswerTo: "c2"}); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("answer to a record comment: %v", err)
	}
	ans, err := d.AddComment(ctx, ws.ID, b.ID, store.Comment{ID: "a1", Body: "平安", AnswerTo: "r1"})
	if err != nil || ans.AnswerTo != "r1" {
		t.Fatalf("answer: %v %+v", err, ans)
	}
	if got, err := d.Comment(ctx, ws.ID, "a1"); err != nil || got.AnswerTo != "r1" || got.Nickname != "组员" {
		t.Fatalf("get: %v %+v", err, got)
	}
	if _, err := d.Comment(ctx, ws.ID, "nope"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("get missing: %v", err)
	}
	if del, err := d.DeleteComment(ctx, ws.ID, "r1", a.ID, true); err != nil || del.RollCall {
		t.Fatalf("delete roll call: %v %+v", err, del)
	}
	list, more, err := d.Comments(ctx, ws.ID, 0, 1)
	if err != nil || !more || len(list) != 1 || list[0].ID != "c2" {
		t.Fatalf("page 1: %v %v %+v", err, more, list)
	}
	list, more, _ = d.Comments(ctx, ws.ID, list[0].Seq, 10)
	if more || len(list) != 3 || list[0].ID != "c1" || !list[0].Deleted || list[1].ID != "a1" || list[2].ID != "r1" {
		t.Fatalf("page 2: %v %+v", more, list)
	}
}

func viewLinks(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	ws, a, _, _ := d.CreateWorkspace(ctx, "w", "a")
	tok, err := d.CreateViewLink(ctx, ws.ID, a.ID, time.Now().Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	m, err := d.Authenticate(ctx, ws.ID, tok)
	if err != nil || !m.Viewer || m.Role != store.RoleReadonly || m.ID != a.ID {
		t.Fatalf("view token: %v %+v", err, m)
	}
	if _, err := d.Authenticate(ctx, "other", tok); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("other workspace: %v", err)
	}
	old, _ := d.CreateViewLink(ctx, ws.ID, a.ID, time.Now().Add(-time.Minute))
	if _, err := d.Authenticate(ctx, ws.ID, old); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("expired link: %v", err)
	}
	ai, err := d.CreateAILink(ctx, ws.ID, a.ID, time.Now().Add(time.Hour))
	if err != nil || !strings.HasPrefix(ai, store.AITokenPrefix) {
		t.Fatalf("ai link: %v %q", err, ai)
	}
	if m, err := d.Authenticate(ctx, ws.ID, ai); err != nil || !m.AI || m.Viewer || m.Role != store.RoleLeader || m.ID != a.ID {
		t.Fatalf("ai token: %v %+v", err, m)
	}
	// A view token relabelled as an AI one is not accepted.
	if _, err := d.Authenticate(ctx, ws.ID, store.AITokenPrefix+strings.TrimPrefix(tok, store.ViewTokenPrefix)); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("relabelled view token: %v", err)
	}
	if n, err := d.RevokeViewLinks(ctx, ws.ID, a.ID); err != nil || n != 2 {
		t.Fatalf("revoke: %v %d", err, n)
	}
	for _, k := range []string{tok, ai} {
		if _, err := d.Authenticate(ctx, ws.ID, k); !errors.Is(err, store.ErrUnauthorized) {
			t.Fatalf("revoked link: %v", err)
		}
	}
	at := time.Date(2026, 11, 24, 9, 30, 42, 0, time.UTC)
	if err := d.Touch(ctx, a.ID, at); err != nil {
		t.Fatal(err)
	}
	ms, _ := d.Members(ctx, ws.ID)
	if len(ms) != 1 || ms[0].LastSeen == nil || !ms[0].LastSeen.Equal(at.Truncate(time.Minute)) {
		t.Fatalf("last seen: %+v", ms)
	}
}

// Many devices pushing at once must still get distinct, gap-free seqs. On
// SQLite this is trivially true; on engines with real concurrency it checks
// the workspace row lock.
func concurrentPushes(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	ws, a, _, _ := d.CreateWorkspace(ctx, "w", "a")
	t0 := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	const workers, each = 8, 5
	var wg sync.WaitGroup
	errs := make(chan error, workers)
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			for i := 0; i < each; i++ {
				if _, _, err := d.PushFeatures(ctx, ws.ID, a.ID, []store.Feature{feat(fmt.Sprintf("w%d-%d", w, i), 1, t0)}); err != nil {
					errs <- err
					return
				}
			}
		}(w)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	fs, _, err := d.PullFeatures(ctx, ws.ID, 0, 1000)
	if err != nil || len(fs) != workers*each {
		t.Fatalf("pull: %v %d", err, len(fs))
	}
	for i, f := range fs {
		if f.Seq != int64(i+1) {
			t.Fatalf("seq %d at %d", f.Seq, i)
		}
	}
}

func lockedProps(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	ws, a, _, _ := d.CreateWorkspace(ctx, "w", "a")
	_, b, _, _ := d.Join(ctx, ws.InviteCode, "b")
	t0 := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	withProps := func(v int64, props string) store.Feature {
		f := feat("f1", v, t0.Add(time.Duration(v)*time.Minute))
		f.Properties = []byte(props)
		return f
	}
	lock := store.LockProps("review")

	// A member's new record can't arrive already reviewed.
	if _, _, err := d.PushFeatures(ctx, ws.ID, b.ID, []store.Feature{withProps(1, `{"title":"D1","review":{"state":"approved"}}`)}, lock); err != nil {
		t.Fatal(err)
	}
	// The leader's push isn't locked.
	d.PushFeatures(ctx, ws.ID, a.ID, []store.Feature{withProps(2, `{"title":"D1","review":{"state":"redo","note":"补产状"}}`)})
	// The member edits the record and tries to clear the review; the edit lands, the review stays.
	res, _, _ := d.PushFeatures(ctx, ws.ID, b.ID, []store.Feature{withProps(3, `{"title":"D1 改"}`)}, lock)
	if res[0].Status != store.PushApplied {
		t.Fatalf("member edit: %+v", res)
	}
	fs, _, _ := d.PullFeatures(ctx, ws.ID, 0, 10)
	var p map[string]any
	if len(fs) != 1 || json.Unmarshal(fs[0].Properties, &p) != nil {
		t.Fatalf("pull: %+v", fs)
	}
	r, _ := p["review"].(map[string]any)
	if p["title"] != "D1 改" || r["state"] != "redo" || r["note"] != "补产状" {
		t.Fatalf("locked review not kept: %s", fs[0].Properties)
	}
	h, _ := d.FeatureHistory(ctx, ws.ID, "f1")
	if len(h) != 2 || string(h[1].Properties) != `{"title":"D1"}` {
		t.Fatalf("history: %+v", h)
	}
}

func outings(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	ws, a, _, _ := d.CreateWorkspace(ctx, "w", "组长")
	_, b, _, _ := d.Join(ctx, ws.InviteCode, "组员")
	ws2, c, _, _ := d.CreateWorkspace(ctx, "w2", "别组")
	now := time.Date(2026, 11, 24, 10, 0, 0, 0, time.UTC)
	at := func(h int) *time.Time { t := now.Add(time.Duration(h) * time.Hour); return &t }

	o1, err := d.AddComment(ctx, ws.ID, b.ID, store.Comment{ID: "o1", Body: "去北沟，下午三点回", BackBy: at(5)})
	if err != nil || o1.BackBy == nil || !o1.BackBy.Equal(*at(5)) {
		t.Fatalf("outing: %v %+v", err, o1)
	}
	if again, err := d.AddComment(ctx, ws.ID, b.ID, store.Comment{ID: "o1", Body: "去北沟，下午三点回", BackBy: at(5)}); err != nil || again.Seq != o1.Seq {
		t.Fatalf("retry: %v %+v", err, again)
	}
	if _, err := d.AddComment(ctx, ws.ID, b.ID, store.Comment{ID: "o1", Body: "去北沟，下午三点回", BackBy: at(6)}); !errors.Is(err, store.ErrConflict) {
		t.Fatalf("retry with another time: %v", err)
	}
	if _, err := d.AddComment(ctx, ws.ID, a.ID, store.Comment{ID: "o2", Body: "去南坡", BackBy: at(2)}); err != nil {
		t.Fatal(err)
	}
	if _, err := d.AddComment(ctx, ws2.ID, c.ID, store.Comment{ID: "o3", Body: "别组外出", BackBy: at(1)}); err != nil {
		t.Fatal(err)
	}
	if _, err := d.AddComment(ctx, ws.ID, b.ID, store.Comment{ID: "m1", Body: "普通消息"}); err != nil {
		t.Fatal(err)
	}
	if _, err := d.AddComment(ctx, ws.ID, b.ID, store.Comment{ID: "x", Body: "回来了", BackFrom: "m1"}); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("back from a plain message: %v", err)
	}
	if _, err := d.AddComment(ctx, ws.ID, b.ID, store.Comment{ID: "x", Body: "回来了", BackFrom: "o3"}); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("back from another workspace's outing: %v", err)
	}

	ids := func(due []store.DueOuting) (out []string) {
		for _, o := range due {
			out = append(out, o.WorkspaceID+"/"+o.Comment.ID)
		}
		return out
	}
	if due, err := d.DueOutings(ctx, now.Add(90*time.Minute), 10); err != nil || len(due) != 1 || due[0].Comment.ID != "o3" || due[0].WorkspaceID != ws2.ID {
		t.Fatalf("due at 11:30: %v %v", err, ids(due))
	}
	due, err := d.DueOutings(ctx, now.Add(6*time.Hour), 10)
	if err != nil || len(due) != 3 || due[0].Comment.ID != "o3" || due[1].Comment.ID != "o2" || due[2].Comment.ID != "o1" || due[2].Comment.Nickname != "组员" {
		t.Fatalf("due at 16:00: %v %v", err, ids(due))
	}
	// Reported back, or already notified: no longer due.
	back, err := d.AddComment(ctx, ws.ID, b.ID, store.Comment{ID: "b1", Body: "回营地了", BackFrom: "o1"})
	if err != nil || back.BackFrom != "o1" {
		t.Fatalf("back: %v %+v", err, back)
	}
	if err := d.MarkOverdue(ctx, ws2.ID, "o3"); err != nil {
		t.Fatal(err)
	}
	if err := d.MarkOverdue(ctx, ws.ID, "nope"); err != nil {
		t.Fatalf("mark unknown: %v", err)
	}
	if due, _ := d.DueOutings(ctx, now.Add(6*time.Hour), 10); len(due) != 1 || due[0].Comment.ID != "o2" {
		t.Fatalf("after back and mark: %v", ids(due))
	}
	// Deleting the report back makes the outing due again; deleting the outing clears it.
	if _, err := d.DeleteComment(ctx, ws.ID, "b1", b.ID, false); err != nil {
		t.Fatal(err)
	}
	if due, _ := d.DueOutings(ctx, now.Add(6*time.Hour), 10); len(due) != 2 {
		t.Fatalf("after deleting the report back: %v", ids(due))
	}
	del, err := d.DeleteComment(ctx, ws.ID, "o2", a.ID, true)
	if err != nil || del.BackBy != nil {
		t.Fatalf("delete outing: %v %+v", err, del)
	}
	if due, _ := d.DueOutings(ctx, now.Add(6*time.Hour), 1); len(due) != 1 || due[0].Comment.ID != "o1" {
		t.Fatalf("after deleting an outing: %v", ids(due))
	}
}

func deleteWorkspace(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	gone, gl, gtok, err := d.CreateWorkspace(ctx, "收工的项目", "组长")
	if err != nil {
		t.Fatal(err)
	}
	_, gm, mtok, err := d.Join(ctx, gone.InviteCode, "组员")
	if err != nil {
		t.Fatal(err)
	}
	kept, kl, ktok, err := d.CreateWorkspace(ctx, "还在用的", "组长")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	for _, ws := range []struct{ id, member string }{{gone.ID, gm.ID}, {kept.ID, kl.ID}} {
		if _, _, err := d.PushFeatures(ctx, ws.id, ws.member, []store.Feature{feat("f1", 1, now)}); err != nil {
			t.Fatal(err)
		}
		if _, _, err := d.PushFeatures(ctx, ws.id, ws.member, []store.Feature{feat("f1", 2, now)}); err != nil {
			t.Fatal(err)
		}
		if _, err := d.AddComment(ctx, ws.id, ws.member, store.Comment{ID: "c1", Body: "hi"}); err != nil {
			t.Fatal(err)
		}
		if err := d.PutFileMeta(ctx, ws.id, store.FileMeta{SHA256: "ab", Size: 1, ContentType: "image/jpeg", CreatedAt: now, CreatedBy: ws.member}); err != nil {
			t.Fatal(err)
		}
		if err := d.SetPushTarget(ctx, ws.id, store.PushTarget{MemberID: ws.member, Endpoint: "https://push.example.com/x", Events: []string{"task"}}); err != nil {
			t.Fatal(err)
		}
	}
	view, err := d.CreateViewLink(ctx, gone.ID, gl.ID, now.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if err := d.DeleteWorkspace(ctx, gone.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := d.Workspace(ctx, gone.ID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("deleted workspace still there: %v", err)
	}
	for _, tok := range []string{gtok, mtok, view} {
		if _, err := d.Authenticate(ctx, gone.ID, tok); err == nil {
			t.Fatal("a token of the deleted workspace still works")
		}
	}
	if _, _, _, err := d.Join(ctx, gone.InviteCode, "迟到的人"); err == nil {
		t.Fatal("the deleted workspace's invite still works")
	}
	// The other workspace keeps everything.
	if _, err := d.Authenticate(ctx, kept.ID, ktok); err != nil {
		t.Fatal(err)
	}
	fs, _, err := d.PullFeatures(ctx, kept.ID, 0, 10)
	if err != nil || len(fs) != 1 {
		t.Fatalf("kept features: %v %d", err, len(fs))
	}
	if h, err := d.FeatureHistory(ctx, kept.ID, "f1"); err != nil || len(h) != 1 {
		t.Fatalf("kept history: %v %d", err, len(h))
	}
	if cs, _, err := d.Comments(ctx, kept.ID, 0, 10); err != nil || len(cs) != 1 {
		t.Fatalf("kept comments: %v %d", err, len(cs))
	}
	if _, err := d.FileMeta(ctx, kept.ID, "ab"); err != nil {
		t.Fatal(err)
	}
	if ts, err := d.PushTargets(ctx, kept.ID); err != nil || len(ts) != 1 {
		t.Fatalf("kept push targets: %v %d", err, len(ts))
	}
	all, err := d.Workspaces(ctx)
	if err != nil || len(all) != 1 || all[0].ID != kept.ID {
		t.Fatalf("workspaces after delete: %v %+v", err, all)
	}
	// Deleting twice is not found.
	if err := d.DeleteWorkspace(ctx, gone.ID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("second delete: %v", err)
	}
}

func archive(t *testing.T, open Opener) {
	ctx := context.Background()
	d := open(t)
	ws, _, _, err := d.CreateWorkspace(ctx, "野外一组", "组长")
	if err != nil {
		t.Fatal(err)
	}
	if ws.ArchivedAt != nil {
		t.Fatalf("new workspace archived: %v", ws.ArchivedAt)
	}
	name, yes, no := " 2026 秋季填图 ", true, false
	got, err := d.UpdateWorkspace(ctx, ws.ID, store.WorkspaceUpdate{Name: &name, Archived: &yes})
	if err != nil || got.Name != "2026 秋季填图" || got.ArchivedAt == nil {
		t.Fatalf("archive: %v %+v", err, got)
	}
	read, err := d.Workspace(ctx, ws.ID)
	if err != nil || read.Name != got.Name || read.ArchivedAt == nil || !read.ArchivedAt.Equal(*got.ArchivedAt) {
		t.Fatalf("read archived: %v %+v", err, read)
	}
	if again, err := d.UpdateWorkspace(ctx, ws.ID, store.WorkspaceUpdate{Archived: &yes}); err != nil || !again.ArchivedAt.Equal(*got.ArchivedAt) {
		t.Fatalf("archive twice keeps the first time: %v %+v", err, again)
	}
	if _, _, _, err := d.Join(ctx, ws.InviteCode, "新人"); !errors.Is(err, store.ErrArchived) {
		t.Fatalf("join archived: %v", err)
	}
	blank := "  "
	if _, err := d.UpdateWorkspace(ctx, ws.ID, store.WorkspaceUpdate{Name: &blank}); !errors.Is(err, store.ErrInvalid) {
		t.Fatalf("blank name: %v", err)
	}
	if _, err := d.UpdateWorkspace(ctx, "nope", store.WorkspaceUpdate{Archived: &no}); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("missing workspace: %v", err)
	}
	if got, err := d.UpdateWorkspace(ctx, ws.ID, store.WorkspaceUpdate{Archived: &no}); err != nil || got.ArchivedAt != nil || got.Name != "2026 秋季填图" {
		t.Fatalf("reopen: %v %+v", err, got)
	}
	if _, _, _, err := d.Join(ctx, ws.InviteCode, "新人"); err != nil {
		t.Fatalf("join reopened: %v", err)
	}
}

package sqlite

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/geocrew-server/internal/store"
)

func open(t *testing.T) *DB {
	t.Helper()
	d, err := Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	return d
}

func TestWorkspaceLifecycle(t *testing.T) {
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

func TestPushPullConflict(t *testing.T) {
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

func TestFileMetaAndReopen(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "g.db")
	d, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	ws, m, tok, _ := d.CreateWorkspace(ctx, "w", "a")
	meta := store.FileMeta{SHA256: "ab", Size: 3, ContentType: "image/jpeg", CreatedAt: time.Now(), CreatedBy: m.ID}
	if err := d.PutFileMeta(ctx, ws.ID, meta); err != nil {
		t.Fatal(err)
	}
	if err := d.PutFileMeta(ctx, ws.ID, meta); err != nil {
		t.Fatalf("duplicate meta: %v", err)
	}
	d.Close()

	d, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	if _, err := d.Authenticate(ctx, ws.ID, tok); err != nil {
		t.Fatalf("token after reopen: %v", err)
	}
	got, err := d.FileMeta(ctx, ws.ID, "ab")
	if err != nil || got.Size != 3 {
		t.Fatalf("file meta: %v %+v", err, got)
	}
	if _, err := d.FileMeta(ctx, ws.ID, "cd"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("missing meta: %v", err)
	}
}

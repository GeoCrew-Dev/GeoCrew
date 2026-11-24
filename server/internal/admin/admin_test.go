package admin

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqldb"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
)

func open(t *testing.T) *sqlite.DB {
	t.Helper()
	db, err := sqlite.Open(filepath.Join(t.TempDir(), "geocrew.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func run(t *testing.T, st store.Store, args ...string) (string, error) {
	t.Helper()
	var out bytes.Buffer
	err := Run(context.Background(), st, args, &out)
	return out.String(), err
}

func TestRecoverLostLeader(t *testing.T) {
	ctx := context.Background()
	db := open(t)
	ws, lost, _, err := db.CreateWorkspace(ctx, "北山填图", "老王")
	if err != nil {
		t.Fatal(err)
	}

	out, err := run(t, db, "workspaces")
	if err != nil || !strings.Contains(out, "北山填图") || !strings.Contains(out, "老王") {
		t.Fatalf("workspaces: %v\n%s", err, out)
	}

	// The leader's phone is gone: issue a new code, rejoin, promote, revoke the old device.
	out, err = run(t, db, "invite", ws.ID[:6])
	if err != nil {
		t.Fatal(err)
	}
	code := out[strings.LastIndex(out, "：")+len("："):]
	code = strings.Fields(code)[0]
	_, again, _, err := db.Join(ctx, code, "老王（新手机）")
	if err != nil {
		t.Fatalf("join with new code %q: %v", code, err)
	}
	if _, err := run(t, db, "role", ws.ID, again.ID, "leader"); err != nil {
		t.Fatal(err)
	}
	if _, err := run(t, db, "revoke", ws.ID, lost.ID); err != nil {
		t.Fatal(err)
	}
	ms, _ := db.Members(ctx, ws.ID)
	for _, m := range ms {
		if m.ID == again.ID && m.Role != store.RoleLeader {
			t.Fatalf("new device should lead, got %s", m.Role)
		}
		if m.ID == lost.ID && !m.Revoked {
			t.Fatal("old device should be revoked")
		}
	}
	out, _ = run(t, db, "members", ws.ID)
	if !strings.Contains(out, "已移出") {
		t.Fatalf("members should show the revoked device:\n%s", out)
	}
}

func TestKeepsLastLeaderAndRejectsBadInput(t *testing.T) {
	ctx := context.Background()
	db := open(t)
	ws, leader, _, _ := db.CreateWorkspace(ctx, "W", "L")
	if _, err := run(t, db, "role", ws.ID, leader.ID, "member"); err == nil || !strings.Contains(err.Error(), "组长") {
		t.Fatalf("demoting the last leader should fail, got %v", err)
	}
	if _, err := run(t, db, "role", ws.ID, leader.ID, "boss"); err == nil {
		t.Fatal("unknown role accepted")
	}
	if _, err := run(t, db, "members", "nope"); err == nil {
		t.Fatal("unknown workspace accepted")
	}
	if _, err := run(t, db, "frobnicate"); err == nil {
		t.Fatal("unknown command accepted")
	}
	if out, err := run(t, db); err != nil || !strings.Contains(out, "用法") {
		t.Fatalf("no args should print usage: %v", err)
	}
}

func TestBackup(t *testing.T) {
	ctx := context.Background()
	db := open(t)
	db.CreateWorkspace(ctx, "W", "L")
	dest := filepath.Join(t.TempDir(), "copy.db")
	if _, err := run(t, db, "backup", dest); err != nil {
		t.Fatal(err)
	}
	copy, err := sqlite.Open(dest)
	if err != nil {
		t.Fatal(err)
	}
	defer copy.Close()
	all, err := copy.Workspaces(ctx)
	if err != nil || len(all) != 1 || all[0].Name != "W" {
		t.Fatalf("backup content: %v %v", all, err)
	}
	if _, err := run(t, db, "backup", dest); err == nil {
		t.Fatal("backup must not overwrite")
	}
	if _, err := os.Stat(dest); err != nil {
		t.Fatal(err)
	}
}

func TestExport(t *testing.T) {
	ctx := context.Background()
	db := open(t)
	ws, _, _, err := db.CreateWorkspace(ctx, "北山填图", "老王")
	if err != nil {
		t.Fatal(err)
	}
	dest := filepath.Join(t.TempDir(), "out.geojson")
	out, err := run(t, db, "export", ws.ID[:6], dest)
	if err != nil || !strings.Contains(out, "0 条记录") {
		t.Fatalf("export: %q %v", out, err)
	}
	if _, err := run(t, db, "export", ws.ID, dest); err == nil {
		t.Fatal("export must not overwrite")
	}
	if b, _ := os.ReadFile(dest); !strings.HasPrefix(string(b), `{"type":"FeatureCollection"`) {
		t.Fatalf("content: %s", b)
	}
	pkg := filepath.Join(t.TempDir(), "北山.zip")
	out, err = run(t, db, "export", ws.ID, pkg)
	if err != nil || !strings.Contains(out, "0 张照片") {
		t.Fatalf("package: %q %v", out, err)
	}
	if b, _ := os.ReadFile(pkg); !strings.HasPrefix(string(b), "PK") {
		t.Fatalf("package content: %q", b[:min(len(b), 16)])
	}
	gpkg := filepath.Join(t.TempDir(), "北山.gpkg")
	out, err = run(t, db, "export", ws.ID, gpkg)
	if err != nil || !strings.Contains(out, "0 条记录") {
		t.Fatalf("gpkg: %q %v", out, err)
	}
	if b, _ := os.ReadFile(gpkg); !strings.HasPrefix(string(b), "SQLite format 3") {
		t.Fatalf("gpkg content: %q", b[:min(len(b), 16)])
	}
}

func TestFilesAndPrune(t *testing.T) {
	ctx := context.Background()
	db := open(t)
	ws, leader, _, err := db.CreateWorkspace(ctx, "北山填图", "老王")
	if err != nil {
		t.Fatal(err)
	}
	disk := &blob.Disk{Root: t.TempDir()}
	put := func(body string, age time.Duration) string {
		sum := sha256.Sum256([]byte(body))
		sha := hex.EncodeToString(sum[:])
		if _, err := disk.Put(ws.ID, sha, strings.NewReader(body)); err != nil {
			t.Fatal(err)
		}
		old := time.Now().Add(-age)
		os.Chtimes(filepath.Join(disk.Root, ws.ID, sha[:2], sha), old, old)
		return sha
	}
	current := put("current", 48*time.Hour)
	historic := put("historic", 48*time.Hour)
	stale := put("stale", 48*time.Hour)
	fresh := put("fresh", time.Minute)
	missing := strings.Repeat("ab", 32)

	feature := func(v int64, photos ...string) store.Feature {
		props, _ := json.Marshal(map[string]any{"title": "D1", "photos": photos})
		return store.Feature{
			ID: "f1", Geometry: json.RawMessage(`{"type":"Point","coordinates":[1,2]}`), Properties: props,
			Version: v, UpdatedAt: time.Now().Add(time.Duration(v) * time.Second), UpdatedBy: leader.ID,
		}
	}
	for _, f := range []store.Feature{feature(1, current, historic), feature(2, current, missing)} {
		if _, _, err := db.PushFeatures(ctx, ws.ID, leader.ID, []store.Feature{f}); err != nil {
			t.Fatal(err)
		}
	}

	runFiles := func(args ...string) string {
		var out bytes.Buffer
		if err := RunWith(ctx, db, Options{Files: disk}, append([]string{"files", ws.ID}, args...), &out); err != nil {
			t.Fatal(err)
		}
		return out.String()
	}
	out := runFiles()
	for _, want := range []string{"4 个", "被记录引用：2 个", "没有记录引用：2 个", "服务器上没有：1 个"} {
		if !strings.Contains(out, want) {
			t.Fatalf("report lacks %q:\n%s", want, out)
		}
	}
	out = runFiles("prune")
	if !strings.Contains(out, "已删除 1 个") {
		t.Fatalf("prune: %s", out)
	}
	left, _ := disk.List(ws.ID)
	kept := map[string]bool{}
	for _, f := range left {
		kept[f.SHA] = true
	}
	if !kept[current] || !kept[historic] || !kept[fresh] || kept[stale] {
		t.Fatalf("kept %v", kept)
	}
}

func TestCopy(t *testing.T) {
	ctx := context.Background()
	db := open(t)
	db.CreateWorkspace(ctx, "W", "L")
	dest := filepath.Join(t.TempDir(), "moved.db")
	opt := Options{OpenDatabase: func(target string) (*sqldb.DB, error) { return sqlite.Open(target) }}
	var out bytes.Buffer
	if err := RunWith(ctx, db, opt, []string{"copy", dest}, &out); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "工作组 1，成员 1") {
		t.Fatalf("summary: %s", out.String())
	}
	if err := RunWith(ctx, db, opt, []string{"copy", dest}, &out); err == nil {
		t.Fatal("copied twice into the same database")
	}
	if _, err := run(t, db, "copy", dest); err == nil {
		t.Fatal("copy without a way to open the target")
	}
}

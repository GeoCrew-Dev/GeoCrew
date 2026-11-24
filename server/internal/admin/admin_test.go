package admin

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
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

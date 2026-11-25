package overview

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/backup"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
)

func TestBuild(t *testing.T) {
	ctx := context.Background()
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "geocrew.db")
	db, err := sqlite.Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	busy, leader, _, err := db.CreateWorkspace(ctx, "东山填图", "老王")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, _, err := db.Join(ctx, busy.InviteCode, "小李"); err != nil {
		t.Fatal(err)
	}
	quiet, _, _, err := db.CreateWorkspace(ctx, "空组", "老张")
	if err != nil {
		t.Fatal(err)
	}
	at := time.Now().Add(-time.Hour).Truncate(time.Second)
	point := func(id string, v int64, deleted bool) store.Feature {
		return store.Feature{
			ID: id, Geometry: json.RawMessage(`{"type":"Point","coordinates":[1,2]}`), Properties: json.RawMessage(`{"title":"D"}`),
			Version: v, UpdatedAt: at.Add(time.Duration(v) * time.Second), UpdatedBy: leader.ID, Deleted: deleted,
		}
	}
	for _, f := range []store.Feature{point("a", 1, false), point("b", 1, false), point("a", 2, false), point("b", 2, true)} {
		if _, _, err := db.PushFeatures(ctx, busy.ID, leader.ID, []store.Feature{f}); err != nil {
			t.Fatal(err)
		}
	}
	disk := &blob.Disk{Root: filepath.Join(dir, "files"), MaxSize: 1 << 20}
	sum := sha256.Sum256([]byte("photo"))
	if _, err := disk.Put(busy.ID, hex.EncodeToString(sum[:]), strings.NewReader("photo")); err != nil {
		t.Fatal(err)
	}
	backups := filepath.Join(dir, "backups")
	if _, err := backup.Once(ctx, db, backup.Options{Dir: backups, Keep: 3}); err != nil {
		t.Fatal(err)
	}

	ov, err := Build(ctx, db, Options{Files: disk, DataDir: dir, Database: dbPath, BackupDir: backups})
	if err != nil {
		t.Fatal(err)
	}
	if len(ov.Workspaces) != 2 || ov.Members != 3 || ov.Records != 1 || ov.FileBytes != 5 {
		t.Fatalf("totals: %+v", ov)
	}
	first := ov.Workspaces[0]
	if first.ID != busy.ID {
		t.Fatalf("the active workspace should come first: %+v", ov.Workspaces)
	}
	if first.Members != 2 || first.Records != 1 || first.Changes != 4 || first.Files != 1 || len(first.Leaders) != 1 || first.Leaders[0] != "老王" {
		t.Fatalf("busy row: %+v", first)
	}
	if !first.LastActive.Equal(at.Add(2 * time.Second)) {
		t.Fatalf("last active %v, want %v", first.LastActive, at.Add(2*time.Second))
	}
	if ov.Workspaces[1].ID != quiet.ID || !ov.Workspaces[1].LastActive.IsZero() || ov.Workspaces[1].Records != 0 {
		t.Fatalf("quiet row: %+v", ov.Workspaces[1])
	}
	if ov.DatabaseBytes <= 0 || len(ov.Backups) != 1 || ov.Backups[0].Bytes <= 0 || ov.Backups[0].At.IsZero() {
		t.Fatalf("files: db %d backups %+v", ov.DatabaseBytes, ov.Backups)
	}
	if _, err := os.Stat(filepath.Join(backups, ov.Backups[0].Name)); err != nil {
		t.Fatal(err)
	}
	if ov.FreeBytes == 0 {
		t.Fatalf("free space should be known or -1, got 0")
	}
}

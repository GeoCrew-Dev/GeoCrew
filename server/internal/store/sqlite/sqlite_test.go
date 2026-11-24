package sqlite

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/storetest"
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

// An existing database from before a migration keeps its data and gains the new tables.
func TestMigratesOlderDatabase(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "old.db")
	full := migrations
	migrations = full[:1]
	d, err := Open(path)
	migrations = full
	if err != nil {
		t.Fatal(err)
	}
	ws, m, tok, _ := d.CreateWorkspace(ctx, "w", "a")
	d.Close()

	d, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	if _, err := d.Authenticate(ctx, ws.ID, tok); err != nil {
		t.Fatalf("token after migration: %v", err)
	}
	if _, v, err := d.Settings(ctx, ws.ID); err != nil || v != 0 {
		t.Fatalf("settings after migration: %v %d", err, v)
	}
	c, err := d.AddComment(ctx, ws.ID, m.ID, store.Comment{ID: "c1", Body: "hi"})
	if err != nil || c.Seq != 1 || c.Nickname != "a" {
		t.Fatalf("comment after migration: %v %+v", err, c)
	}
}

func TestContract(t *testing.T) {
	storetest.Run(t, func(t *testing.T) store.Store { return open(t) })
}

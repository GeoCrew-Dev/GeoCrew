package backup

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
)

func TestOnceSnapshotsMirrorsAndPrunes(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(filepath.Join(t.TempDir(), "geocrew.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, _, _, err := db.CreateWorkspace(ctx, "W", "L"); err != nil {
		t.Fatal(err)
	}
	files := t.TempDir()
	write(t, filepath.Join(files, "ws1", "ab", "abcd"), "photo")
	write(t, filepath.Join(files, "ws1", "ab", ".upload-1"), "half")
	dir := filepath.Join(t.TempDir(), "backups")
	clock := time.Date(2026, 11, 25, 1, 0, 0, 0, time.UTC)
	o := Options{Dir: dir, Keep: 2, Files: files, Now: func() time.Time { return clock }}

	for i := 0; i < 3; i++ {
		name, err := Once(ctx, db, o)
		if err != nil {
			t.Fatal(err)
		}
		if filepath.Base(name) != "geocrew-"+clock.Format("20060102-150405")+".db" {
			t.Fatalf("name %s", name)
		}
		clock = clock.Add(time.Hour)
	}
	snaps, err := Snapshots(dir)
	if err != nil || len(snaps) != 2 {
		t.Fatalf("kept %v %v", snaps, err)
	}
	if filepath.Base(snaps[0]) != "geocrew-20261125-020000.db" {
		t.Fatalf("oldest kept %s", snaps[0])
	}
	copy, err := sqlite.Open(snaps[1])
	if err != nil {
		t.Fatal(err)
	}
	defer copy.Close()
	if all, err := copy.Workspaces(ctx); err != nil || len(all) != 1 {
		t.Fatalf("snapshot content %v %v", all, err)
	}
	if b, err := os.ReadFile(filepath.Join(dir, "files", "ws1", "ab", "abcd")); err != nil || string(b) != "photo" {
		t.Fatalf("mirror %q %v", b, err)
	}
	if _, err := os.Stat(filepath.Join(dir, "files", "ws1", "ab", ".upload-1")); err == nil {
		t.Fatal("uploads in progress must not be mirrored")
	}

	// A file removed from the server stays in the mirror; a new one is added.
	os.Remove(filepath.Join(files, "ws1", "ab", "abcd"))
	write(t, filepath.Join(files, "ws2", "cd", "cdef"), "audio")
	if _, err := Once(ctx, db, o); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{"ws1/ab/abcd", "ws2/cd/cdef"} {
		if _, err := os.Stat(filepath.Join(dir, "files", filepath.FromSlash(p))); err != nil {
			t.Fatalf("%s: %v", p, err)
		}
	}
}

func TestOnceRefusesExistingAndMissingFiles(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(filepath.Join(t.TempDir(), "geocrew.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	at := time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)
	o := Options{Dir: t.TempDir(), Keep: 3, Files: filepath.Join(t.TempDir(), "none"), Now: func() time.Time { return at }}
	if _, err := Once(ctx, db, o); err != nil {
		t.Fatalf("no attachments yet should be fine: %v", err)
	}
	if _, err := Once(ctx, db, o); err == nil {
		t.Fatal("same timestamp must not overwrite")
	}
	write(t, filepath.Join(o.Dir, "geocrew-notes.db"), "x")
	snaps, _ := Snapshots(o.Dir)
	if len(snaps) != 1 {
		t.Fatalf("only real snapshots count: %v", snaps)
	}
}

func write(t *testing.T, path, body string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(body), 0o640); err != nil {
		t.Fatal(err)
	}
}

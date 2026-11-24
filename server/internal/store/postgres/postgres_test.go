package postgres

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"net/url"
	"os"
	"strings"
	"testing"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/storetest"
)

// The contract runs only against a real server: set GEOCREW_TEST_POSTGRES to
// a URL such as postgres://postgres:postgres@localhost:5432/postgres (CI does).
// Each test gets a schema of its own, dropped afterwards.
func dsn(t *testing.T) string {
	base := os.Getenv("GEOCREW_TEST_POSTGRES")
	if base == "" {
		t.Skip("GEOCREW_TEST_POSTGRES not set")
	}
	admin, err := sql.Open("pgx", base)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { admin.Close() })
	b := make([]byte, 6)
	rand.Read(b)
	schema := "geocrew_test_" + hex.EncodeToString(b)
	if _, err := admin.Exec(`CREATE SCHEMA ` + schema); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { admin.Exec(`DROP SCHEMA ` + schema + ` CASCADE`) })
	u, err := url.Parse(base)
	if err != nil || !strings.HasPrefix(u.Scheme, "postgres") {
		t.Fatalf("GEOCREW_TEST_POSTGRES must be a postgres:// URL")
	}
	q := u.Query()
	q.Set("search_path", schema)
	u.RawQuery = q.Encode()
	return u.String()
}

func open(t *testing.T) *DB {
	d, err := Open(dsn(t))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { d.Close() })
	return d
}

func TestContract(t *testing.T) {
	storetest.Run(t, func(t *testing.T) store.Store { return open(t) })
}

// Opening again finds the schema in place and migrates nothing twice.
func TestReopen(t *testing.T) {
	url := dsn(t)
	a, err := Open(url)
	if err != nil {
		t.Fatal(err)
	}
	ws, _, tok, err := a.CreateWorkspace(t.Context(), "w", "a")
	if err != nil {
		t.Fatal(err)
	}
	a.Close()
	b, err := Open(url)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if _, err := b.Authenticate(t.Context(), ws.ID, tok); err != nil {
		t.Fatalf("token after reopen: %v", err)
	}
	if err := b.Backup(t.Context(), "x"); err == nil {
		t.Fatal("backup should point to pg_dump")
	}
}

package postgres

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store/sqlite"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store/storetest"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1/v1test"
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

// Moving a server from SQLite to PostgreSQL and back keeps everything.
func TestCopyBetweenEngines(t *testing.T) {
	ctx := t.Context()
	lite, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer lite.Close()
	ws, m, tok, _ := lite.CreateWorkspace(ctx, "野外一组", "组长")
	at := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	lite.PushFeatures(ctx, ws.ID, m.ID, []store.Feature{{ID: "f1", Version: 1, UpdatedAt: at, Geometry: []byte(`{"type":"Point","coordinates":[116.4,39.9]}`), Properties: []byte(`{"title":"D001"}`)}})
	lite.AddComment(ctx, ws.ID, m.ID, store.Comment{ID: "c1", Body: "集合", At: []float64{116.4, 39.9}})
	lite.SetPushTarget(ctx, ws.ID, store.PushTarget{MemberID: m.ID, Endpoint: "https://a/b", Events: []string{"task"}})
	lite.PutFileMeta(ctx, ws.ID, store.FileMeta{SHA256: "ab", Size: 3, ContentType: "image/jpeg", CreatedAt: at, CreatedBy: m.ID})
	j, err := lite.V1().CreateWorkspace(ctx, "v1 一组", "组长", "S24")
	if err != nil {
		t.Fatal(err)
	}
	pg := open(t)
	if _, err := lite.CopyTo(ctx, pg); err != nil {
		t.Fatal(err)
	}
	if m, _, err := pg.V1().Authenticate(ctx, j.Workspace.ID, j.Token); err != nil || m.ID != j.Member.ID {
		t.Fatalf("v1 token: %v %+v", err, m)
	}
	if _, err := pg.Authenticate(ctx, ws.ID, tok); err != nil {
		t.Fatalf("token: %v", err)
	}
	if fs, _, _ := pg.PullFeatures(ctx, ws.ID, 0, 10); len(fs) != 1 || string(fs[0].Properties) != `{"title":"D001"}` || !fs[0].UpdatedAt.Equal(at) {
		t.Fatalf("features: %+v", fs)
	}
	if c, err := pg.AddComment(ctx, ws.ID, m.ID, store.Comment{ID: "c2", Body: "到了"}); err != nil || c.Seq != 2 {
		t.Fatalf("comment seq continues: %v %+v", err, c)
	}
	back, _ := sqlite.Open(":memory:")
	defer back.Close()
	counts, err := pg.CopyTo(ctx, back)
	if err != nil || counts["comments"] != 2 || counts["push_targets"] != 1 || counts["files"] != 1 || counts["v1_roles"] != 1 {
		t.Fatalf("back to sqlite: %v %v", err, counts)
	}
}

func TestContractV1(t *testing.T) {
	v1test.Run(t, func(t *testing.T, now func() time.Time) v1.Store { return open(t).V1().WithClock(now) })
}

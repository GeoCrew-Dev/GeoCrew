package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestCompare(t *testing.T) {
	for _, c := range []struct {
		a, b string
		want int
	}{
		{"1.2.3", "1.2.3", 0},
		{"v1.2.3", "1.2.3", 0},
		{"1.2.10", "1.2.9", 1},
		{"1.3", "1.2.9", 1},
		{"1.2.3-rc.1", "1.2.3", -1},
		{"1.2.3", "1.2.3-rc.1", 1},
		{"1.2.3-rc.2", "1.2.3-rc.1", 1},
		{"dev", "0.0.1", -1},
		{"0.1.0+abc", "0.1.0", 0},
	} {
		if got := Compare(c.a, c.b); got != c.want {
			t.Errorf("Compare(%q, %q) = %d, want %d", c.a, c.b, got, c.want)
		}
	}
	if Newer("1.0.0", "dev") {
		t.Error("a development build must not be offered an update")
	}
	if !Newer("1.0.1", "1.0.0") || Newer("1.0.0", "1.0.0") {
		t.Error("Newer")
	}
}

func TestSumFor(t *testing.T) {
	h := strings.Repeat("ab", 32)
	sums := []byte(h + "  geocrew-server-linux-amd64\n" + strings.Repeat("cd", 32) + " *geocrew-server-windows-amd64.exe\n")
	if got, err := SumFor(sums, "geocrew-server-linux-amd64"); err != nil || got != h {
		t.Fatal(got, err)
	}
	if _, err := SumFor(sums, "geocrew-server-windows-amd64.exe"); err != nil {
		t.Fatal(err)
	}
	if _, err := SumFor(sums, "geocrew-server-linux-arm64"); err == nil {
		t.Fatal("missing name must fail")
	}
	if _, err := SumFor([]byte("xyz  geocrew-server-linux-amd64\n"), "geocrew-server-linux-amd64"); err == nil {
		t.Fatal("a bad hash must fail")
	}
}

// feed serves a release whose binary for this platform is a script that
// prints version.
func feed(t *testing.T, version string, corrupt bool) *httptest.Server {
	t.Helper()
	bin := []byte("#!/bin/sh\necho geocrew-server " + version + "\n")
	sum := sha256.Sum256(bin)
	if corrupt {
		sum[0] ^= 1
	}
	name := AssetName(runtime.GOOS, runtime.GOARCH)
	mux := http.NewServeMux()
	var srv *httptest.Server
	mux.HandleFunc("/latest", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, `{"tag_name":"v%s","name":"同勘 %s","body":"### 新功能\n- 归档","html_url":"https://example.org/r","published_at":"2026-11-25T03:00:00Z",
		"assets":[{"name":%q,"browser_download_url":"%s/bin"},{"name":"SHA256SUMS","browser_download_url":"%s/sums"}]}`, version, version, name, srv.URL, srv.URL)
	})
	mux.HandleFunc("/bin", func(w http.ResponseWriter, r *http.Request) { w.Write(bin) })
	mux.HandleFunc("/sums", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "%s  %s\n", hex.EncodeToString(sum[:]), name)
	})
	srv = httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func TestLatestAndDownload(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the fake binary is a shell script")
	}
	srv := feed(t, "1.4.0", false)
	r, err := (&Checker{URL: srv.URL + "/latest"}).Latest(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if r.Version != "1.4.0" || r.Tag != "v1.4.0" || !strings.Contains(r.Notes, "归档") || r.PublishedAt.IsZero() {
		t.Fatalf("%+v", r)
	}
	dir := t.TempDir()
	dest := filepath.Join(dir, "geocrew-server")
	got, err := Download(context.Background(), nil, r, runtime.GOOS, runtime.GOARCH, dest)
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Dir(got) != dir {
		t.Fatalf("download must land next to the program, got %s", got)
	}

	bad := feed(t, "1.4.0", true)
	r2, _ := (&Checker{URL: bad.URL + "/latest"}).Latest(context.Background())
	if _, err := Download(context.Background(), nil, r2, runtime.GOOS, runtime.GOARCH, dest); err == nil || !strings.Contains(err.Error(), "校验值不符") {
		t.Fatalf("a wrong hash must be refused, got %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, ".geocrew-server.new")); !os.IsNotExist(err) {
		t.Fatal("a refused download must be removed")
	}
	r3 := r
	r3.Version = "9.9.9"
	if _, err := Download(context.Background(), nil, r3, runtime.GOOS, runtime.GOARCH, dest); err == nil || !strings.Contains(err.Error(), "版本") {
		t.Fatalf("a binary reporting another version must be refused, got %v", err)
	}
}

func TestInstallTrialRollback(t *testing.T) {
	data := t.TempDir()
	bin := filepath.Join(t.TempDir(), "geocrew-server")
	db := filepath.Join(data, "geocrew.db")
	write(t, bin, "old program")
	write(t, db, "before")
	newBin := bin + ".download"
	write(t, newBin, "new program")

	st, err := Install(InstallOptions{
		DataDir: data, Binary: bin, New: newBin, From: "1.0.0", To: "1.1.0", Database: db,
		Snapshot: func(dest string) error { return os.WriteFile(dest, []byte("before"), 0o640) },
	})
	if err != nil {
		t.Fatal(err)
	}
	if read(t, bin) != "new program" || read(t, st.Previous) != "old program" || st.Snapshot == "" {
		t.Fatalf("%+v", st)
	}
	// The new program migrates the database, then fails to start.
	write(t, db, "migrated")
	write(t, db+"-wal", "log")
	st, trial, rollback, err := Begin(data, nil)
	if err != nil || !trial || rollback || st.Starts != 1 {
		t.Fatal(st, trial, rollback, err)
	}
	if _, err := Rollback(data, st, "启动失败"); err != nil {
		t.Fatal(err)
	}
	if read(t, bin) != "old program" || read(t, db) != "before" {
		t.Fatal("old program and database must be back")
	}
	if _, err := os.Stat(db + "-wal"); !os.IsNotExist(err) {
		t.Fatal("the new program's write-ahead log must go")
	}
	got, _, _ := Load(data)
	if got.Status != StatusRolledBack || got.Error != "启动失败" {
		t.Fatalf("%+v", got)
	}
	// The old program starts normally.
	if _, trial, _, _ := Begin(data, nil); trial {
		t.Fatal("no trial after a rollback")
	}
}

func TestCrashedTrialRollsBackOnNextStart(t *testing.T) {
	data := t.TempDir()
	bin := filepath.Join(t.TempDir(), "geocrew-server")
	write(t, bin, "old")
	write(t, bin+".n", "new")
	if _, err := Install(InstallOptions{DataDir: data, Binary: bin, New: bin + ".n", From: "1", To: "2"}); err != nil {
		t.Fatal(err)
	}
	if _, _, rollback, _ := Begin(data, nil); rollback {
		t.Fatal("first start is the trial")
	}
	// It crashed without confirming; the supervisor starts it again.
	if _, _, rollback, _ := Begin(data, nil); !rollback {
		t.Fatal("second start of an unconfirmed trial must roll back")
	}
}

func TestConfirmAndRestorePrevious(t *testing.T) {
	data := t.TempDir()
	bin := filepath.Join(t.TempDir(), "geocrew-server")
	write(t, bin, "old")
	write(t, bin+".n", "new")
	st, err := Install(InstallOptions{DataDir: data, Binary: bin, New: bin + ".n", From: "1.0.0", To: "1.1.0"})
	if err != nil {
		t.Fatal(err)
	}
	st, _, _, _ = Begin(data, nil)
	if err := Confirm(data, st); err != nil {
		t.Fatal(err)
	}
	st, _, _ = Load(data)
	if st.Status != StatusDone {
		t.Fatal(st.Status)
	}
	if _, trial, _, _ := Begin(data, nil); trial {
		t.Fatal("a confirmed update is not a trial")
	}
	if _, err := RestorePrevious(data, st); err != nil {
		t.Fatal(err)
	}
	if read(t, bin) != "old" || read(t, bin+".prev") != "new" {
		t.Fatal("binaries must be swapped")
	}
}

func write(t *testing.T, p, s string) {
	t.Helper()
	if err := os.WriteFile(p, []byte(s), 0o755); err != nil {
		t.Fatal(err)
	}
}

func read(t *testing.T, p string) string {
	t.Helper()
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestFailedCheckKeepsOldProgram(t *testing.T) {
	data := t.TempDir()
	bin := filepath.Join(t.TempDir(), "geocrew-server")
	write(t, bin, "old")
	write(t, bin+".n", "new")
	_, err := Install(InstallOptions{
		DataDir: data, Binary: bin, New: bin + ".n", From: "1", To: "2", Database: filepath.Join(data, "geocrew.db"),
		Snapshot: func(dest string) error { return os.WriteFile(dest, []byte("db"), 0o640) },
		Check:    func(string, string) error { return fmt.Errorf("migration 12 failed") },
	})
	if err == nil || !strings.Contains(err.Error(), "migration 12") {
		t.Fatal(err)
	}
	if read(t, bin) != "old" {
		t.Fatal("the old program must stay")
	}
	if _, ok, _ := Load(data); ok {
		t.Fatal("no trial recorded")
	}
}

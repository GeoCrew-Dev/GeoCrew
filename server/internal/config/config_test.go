package config

import (
	"log/slog"
	"testing"
)

func TestLoad(t *testing.T) {
	t.Setenv("GEOCREW_NAME", "env-name")
	t.Setenv("GEOCREW_MAX_UPLOAD_MB", "8")
	c, err := Load([]string{"-addr", ":9000", "-log-level", "debug"})
	if err != nil {
		t.Fatal(err)
	}
	if c.Addr != ":9000" || c.Name != "env-name" || c.MaxUploadMB != 8 || c.LogLevel != slog.LevelDebug {
		t.Fatalf("unexpected %+v", c)
	}
	if _, err := Load([]string{"-log-format", "xml"}); err == nil {
		t.Fatal("bad format accepted")
	}
	if c.Database != "" {
		t.Fatalf("SQLite should be the default, got %q", c.Database)
	}
	t.Setenv("GEOCREW_DATABASE_URL", "postgres://geocrew@db/geocrew")
	if c, err := Load(nil); err != nil || c.Database != "postgres://geocrew@db/geocrew" {
		t.Fatalf("database url: %v %q", err, c.Database)
	}
	if _, err := Load([]string{"-database", "mysql://x"}); err == nil {
		t.Fatal("non-postgres database accepted")
	}
}

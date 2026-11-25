// Package config loads server settings from flags and GEOCREW_* environment
// variables (flags win), so the same binary works on bare metal and in Docker.
package config

import (
	"flag"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config holds every runtime setting.
type Config struct {
	Addr    string
	Name    string
	DataDir string
	// Database is a PostgreSQL URL; empty keeps the SQLite file in DataDir.
	Database       string
	MaxUploadMB    int64
	LogLevel       slog.Level
	LogFormat      string // "text" or "json"
	HealthcheckURL string // set by -healthcheck: probe and exit
	// Args holds what follows the flags, e.g. an "admin" or "backup" command.
	Args          []string
	DisableCreate bool
	TrustProxy    bool
	JoinPerMinute int
	// Push enables notices to members' push endpoints (UnifiedPush).
	Push bool
	// PushAllowPrivate lets endpoints sit on private networks (a LAN ntfy).
	PushAllowPrivate bool
	// BackupDir, when set, receives scheduled database snapshots and an
	// attachment mirror (SQLite only; PostgreSQL has pg_dump).
	BackupDir   string
	BackupEvery time.Duration
	BackupKeep  int
	// MetricsAddr, when set, is a second listener serving /metrics
	// (Prometheus text format); keep it on a private address.
	MetricsAddr string
}

func envBool(k string) bool {
	v, _ := strconv.ParseBool(os.Getenv(k))
	return v
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// Load parses args (without the program name) on top of the environment.
func Load(args []string) (Config, error) {
	fs := flag.NewFlagSet("geocrew-server", flag.ContinueOnError)
	var c Config
	var level string
	maxUpload, err := strconv.ParseInt(env("GEOCREW_MAX_UPLOAD_MB", "32"), 10, 64)
	if err != nil {
		return c, fmt.Errorf("GEOCREW_MAX_UPLOAD_MB: %w", err)
	}
	fs.StringVar(&c.Addr, "addr", env("GEOCREW_ADDR", ":8080"), "listen address")
	fs.StringVar(&c.Name, "name", env("GEOCREW_NAME", "GeoCrew Server"), "server display name")
	fs.StringVar(&c.DataDir, "data", env("GEOCREW_DATA_DIR", "./data"), "directory for the database and attachments")
	fs.StringVar(&c.Database, "database", env("GEOCREW_DATABASE_URL", ""), "PostgreSQL URL (postgres://…); empty uses SQLite in the data directory")
	fs.Int64Var(&c.MaxUploadMB, "max-upload-mb", maxUpload, "largest attachment accepted, in MiB")
	fs.StringVar(&level, "log-level", env("GEOCREW_LOG_LEVEL", "info"), "debug, info, warn or error")
	fs.StringVar(&c.LogFormat, "log-format", env("GEOCREW_LOG_FORMAT", "text"), "text or json")
	fs.StringVar(&c.HealthcheckURL, "healthcheck", "", "probe this URL's /healthz and exit (for container health checks)")
	fs.BoolVar(&c.DisableCreate, "disable-create", envBool("GEOCREW_DISABLE_CREATE"), "reject new workspaces")
	fs.BoolVar(&c.TrustProxy, "trust-proxy", envBool("GEOCREW_TRUST_PROXY"), "use X-Forwarded-For for rate limits (behind a reverse proxy)")
	joinRate, err := strconv.Atoi(env("GEOCREW_JOIN_PER_MINUTE", "10"))
	if err != nil {
		return c, fmt.Errorf("GEOCREW_JOIN_PER_MINUTE: %w", err)
	}
	fs.IntVar(&c.JoinPerMinute, "join-per-minute", joinRate, "create/join attempts allowed per client address per minute")
	pushOn := true
	if v := os.Getenv("GEOCREW_PUSH"); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			return c, fmt.Errorf("GEOCREW_PUSH: %w", err)
		}
		pushOn = b
	}
	fs.BoolVar(&c.Push, "push", pushOn, "send push notices to endpoints members register (UnifiedPush)")
	fs.BoolVar(&c.PushAllowPrivate, "push-allow-private", envBool("GEOCREW_PUSH_ALLOW_PRIVATE"),
		"allow push endpoints on private or loopback addresses and plain http (LAN distributors)")
	fs.StringVar(&c.BackupDir, "backup-dir", env("GEOCREW_BACKUP_DIR", ""), "directory for scheduled backups; empty turns them off")
	every, err := time.ParseDuration(env("GEOCREW_BACKUP_EVERY", "24h"))
	if err != nil {
		return c, fmt.Errorf("GEOCREW_BACKUP_EVERY: %w", err)
	}
	fs.DurationVar(&c.BackupEvery, "backup-every", every, "time between scheduled backups")
	keep, err := strconv.Atoi(env("GEOCREW_BACKUP_KEEP", "7"))
	if err != nil {
		return c, fmt.Errorf("GEOCREW_BACKUP_KEEP: %w", err)
	}
	fs.IntVar(&c.BackupKeep, "backup-keep", keep, "how many scheduled backups to keep")
	fs.StringVar(&c.MetricsAddr, "metrics-addr", env("GEOCREW_METRICS_ADDR", ""), "serve /metrics on this address, e.g. 127.0.0.1:9180; empty turns it off")
	if err := fs.Parse(args); err != nil {
		return c, err
	}
	c.Args = fs.Args()
	if err := c.LogLevel.UnmarshalText([]byte(strings.ToUpper(level))); err != nil {
		return c, fmt.Errorf("log level: %w", err)
	}
	if c.LogFormat != "text" && c.LogFormat != "json" {
		return c, fmt.Errorf("log format must be text or json")
	}
	if c.Database != "" && !strings.HasPrefix(c.Database, "postgres://") && !strings.HasPrefix(c.Database, "postgresql://") {
		return c, fmt.Errorf("database must be a postgres:// URL (leave it empty for SQLite)")
	}
	if c.JoinPerMinute < 1 {
		return c, fmt.Errorf("join-per-minute must be at least 1")
	}
	if c.BackupDir != "" && c.BackupEvery < 10*time.Minute {
		return c, fmt.Errorf("backup-every must be at least 10m")
	}
	if c.BackupKeep < 1 {
		return c, fmt.Errorf("backup-keep must be at least 1")
	}
	if c.MaxUploadMB < 1 {
		return c, fmt.Errorf("max upload must be at least 1 MiB")
	}
	return c, nil
}

// Logger builds the slog logger described by c.
func (c Config) Logger() *slog.Logger {
	opts := &slog.HandlerOptions{Level: c.LogLevel}
	if c.LogFormat == "json" {
		return slog.New(slog.NewJSONHandler(os.Stderr, opts))
	}
	return slog.New(slog.NewTextHandler(os.Stderr, opts))
}

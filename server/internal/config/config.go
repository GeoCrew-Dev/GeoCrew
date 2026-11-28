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

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/update"
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
	// ShowVersion prints the version and exits (-version).
	ShowVersion bool
	// SelfCheck opens and migrates this SQLite file, then exits: an update
	// runs it on a copy of the database before switching programs.
	SelfCheck string
	// AdminAddr, when set, is a private listener for the operator console
	// (version, release notes, one-click update); AdminToken guards it.
	AdminAddr  string
	AdminToken string
	// UpdateURL is the release feed; UpdateCheck logs when a newer release
	// is out (once a day).
	UpdateURL   string
	UpdateCheck bool
	// TLS is how the main listener speaks (ADR 0014): "self" serves a
	// certificate the server makes on first start and keeps in DataDir/tls,
	// whose public key phones pin; "files" serves TLSCert and TLSKey (Let's
	// Encrypt, a company's own); "off" speaks plain HTTP, behind a reverse
	// proxy that does TLS, or on a test machine.
	TLS     string
	TLSCert string
	TLSKey  string
}

// TLS modes.
const (
	TLSSelf  = "self"
	TLSFiles = "files"
	TLSOff   = "off"
)

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
	fs.BoolVar(&c.ShowVersion, "version", false, "print the version and exit")
	fs.StringVar(&c.SelfCheck, "selfcheck", "", "open and migrate this SQLite file, then exit (used by updates)")
	fs.StringVar(&c.AdminAddr, "admin-addr", env("GEOCREW_ADMIN_ADDR", ""), "serve the operator console on this address, e.g. 127.0.0.1:9181; empty turns it off")
	fs.StringVar(&c.AdminToken, "admin-token", env("GEOCREW_ADMIN_TOKEN", ""), "token for the operator console; empty generates one in the data directory")
	fs.StringVar(&c.UpdateURL, "update-url", env("GEOCREW_UPDATE_URL", update.DefaultURL), "release feed (GitHub API format: a release list, or one release) for update checks")
	updateCheck := true
	if v := os.Getenv("GEOCREW_UPDATE_CHECK"); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			return c, fmt.Errorf("GEOCREW_UPDATE_CHECK: %w", err)
		}
		updateCheck = b
	}
	fs.BoolVar(&c.UpdateCheck, "update-check", updateCheck, "check the release feed once a day and log when a newer version is out")
	fs.StringVar(&c.TLS, "tls", env("GEOCREW_TLS", TLSSelf), "self (a certificate made on first start, pinned by the phones), files (-tls-cert and -tls-key) or off (plain HTTP, behind a TLS proxy)")
	fs.StringVar(&c.TLSCert, "tls-cert", env("GEOCREW_TLS_CERT", ""), "certificate chain (PEM) for -tls files")
	fs.StringVar(&c.TLSKey, "tls-key", env("GEOCREW_TLS_KEY", ""), "private key (PEM) for -tls files")
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
	if c.AdminToken != "" && len(c.AdminToken) < 16 {
		return c, fmt.Errorf("admin token must be at least 16 characters")
	}
	if c.MaxUploadMB < 1 {
		return c, fmt.Errorf("max upload must be at least 1 MiB")
	}
	switch c.TLS {
	case TLSSelf, TLSOff:
	case TLSFiles:
		if c.TLSCert == "" || c.TLSKey == "" {
			return c, fmt.Errorf("tls files needs -tls-cert and -tls-key")
		}
	default:
		return c, fmt.Errorf("tls must be self, files or off")
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

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
)

// Config holds every runtime setting.
type Config struct {
	Addr           string
	Name           string
	DataDir        string
	MaxUploadMB    int64
	LogLevel       slog.Level
	LogFormat      string // "text" or "json"
	HealthcheckURL string // set by -healthcheck: probe and exit
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
	fs.Int64Var(&c.MaxUploadMB, "max-upload-mb", maxUpload, "largest attachment accepted, in MiB")
	fs.StringVar(&level, "log-level", env("GEOCREW_LOG_LEVEL", "info"), "debug, info, warn or error")
	fs.StringVar(&c.LogFormat, "log-format", env("GEOCREW_LOG_FORMAT", "text"), "text or json")
	fs.StringVar(&c.HealthcheckURL, "healthcheck", "", "probe this URL's /healthz and exit (for container health checks)")
	if err := fs.Parse(args); err != nil {
		return c, err
	}
	if err := c.LogLevel.UnmarshalText([]byte(strings.ToUpper(level))); err != nil {
		return c, fmt.Errorf("log level: %w", err)
	}
	if c.LogFormat != "text" && c.LogFormat != "json" {
		return c, fmt.Errorf("log format must be text or json")
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

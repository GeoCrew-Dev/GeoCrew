// Command geocrew-server is the self-hosted GeoCrew team server.
package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/admin"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/api"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/config"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/push"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/realtime"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/postgres"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqldb"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "geocrew-server:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load(os.Args[1:])
	if err != nil {
		return err
	}
	if cfg.HealthcheckURL != "" {
		return healthcheck(cfg.HealthcheckURL)
	}
	log := cfg.Logger()

	if len(cfg.Args) > 0 && cfg.Args[0] != "admin" {
		return fmt.Errorf("unknown command %q (try: admin help)", cfg.Args[0])
	}
	if err := os.MkdirAll(cfg.DataDir, 0o750); err != nil {
		return err
	}
	db, err := openStore(cfg)
	if err != nil {
		return err
	}
	defer db.Close()
	log.Info("database", "engine", db.Dialect())
	files := &blob.Disk{Root: filepath.Join(cfg.DataDir, "files"), MaxSize: cfg.MaxUploadMB << 20}
	if len(cfg.Args) > 0 {
		return admin.RunWith(context.Background(), db, admin.Options{Files: files, OpenDatabase: openTarget}, cfg.Args[1:], os.Stdout)
	}

	var sender *push.Sender
	if cfg.Push {
		sender = push.New(db, push.Options{AllowPrivate: cfg.PushAllowPrivate, Log: log})
		// Deliver what is queued before the database closes.
		defer sender.Close()
	}
	srv := &api.Server{
		Name:    cfg.Name,
		Version: version,
		Store:   db,
		Files:   files,
		Hub:     realtime.NewHub(),
		Log:     log,
		Push:    sender,

		DisableCreate: cfg.DisableCreate,
		TrustProxy:    cfg.TrustProxy,
		JoinPerMinute: cfg.JoinPerMinute,
	}
	httpSrv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	errc := make(chan error, 1)
	go func() {
		log.Info("geocrew-server listening", "addr", cfg.Addr, "protocol", api.ProtocolVersion,
			"version", version, "data", cfg.DataDir)
		errc <- httpSrv.ListenAndServe()
	}()
	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	log.Info("shutting down")
	sctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpSrv.Shutdown(sctx); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func healthcheck(base string) error {
	if !strings.HasPrefix(base, "http") {
		base = "http://" + base
	}
	c := &http.Client{Timeout: 3 * time.Second}
	resp, err := c.Get(strings.TrimRight(base, "/") + "/healthz")
	if err != nil {
		return err
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("healthz returned %d", resp.StatusCode)
	}
	return nil
}

// openStore opens PostgreSQL when a database URL is set, otherwise the
// SQLite file in the data directory. Attachments stay on disk either way.
func openStore(cfg config.Config) (*sqldb.DB, error) {
	if cfg.Database != "" {
		return postgres.Open(cfg.Database)
	}
	return sqlite.Open(filepath.Join(cfg.DataDir, "geocrew.db"))
}

// openTarget opens the database named by "admin copy": a PostgreSQL URL or
// a new SQLite file.
func openTarget(target string) (*sqldb.DB, error) {
	if strings.HasPrefix(target, "postgres://") || strings.HasPrefix(target, "postgresql://") {
		return postgres.Open(target)
	}
	if _, err := os.Stat(target); err == nil {
		return nil, fmt.Errorf("%s 已存在，请换一个新文件名", target)
	}
	return sqlite.Open(target)
}

// Command geocrew-server is the self-hosted GeoCrew team server.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/admin"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/api"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/backup"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/config"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/console"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/push"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/realtime"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/postgres"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqldb"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/update"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	err := run()
	var re restartError
	if errors.As(err, &re) {
		err = update.Exec(re.bin) // returns only if the new process could not start
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "geocrew-server:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load(os.Args[1:])
	if err != nil {
		return err
	}
	if cfg.ShowVersion {
		fmt.Println("geocrew-server", version)
		return nil
	}
	if cfg.SelfCheck != "" {
		db, err := sqlite.Open(cfg.SelfCheck)
		if err != nil {
			return err
		}
		if err := db.Close(); err != nil {
			return err
		}
		fmt.Println("selfcheck ok", version)
		return nil
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
	if len(cfg.Args) > 0 {
		return adminCommand(cfg)
	}

	// After an update the first start is a trial: if the new version fails
	// before it is healthy, the old program and database go back.
	st, trial, rollbackNow, err := update.Begin(cfg.DataDir, log)
	if err != nil {
		log.Warn("update state unreadable", "err", err)
	}
	if rollbackNow {
		return rollback(cfg, st, errors.New("新版本上次启动后没有通过健康检查就退出了"), log)
	}
	if !trial {
		return serve(cfg, log, nil)
	}
	var confirmed atomic.Bool
	err = serve(cfg, log, &trialRun{State: st, confirmed: &confirmed})
	var re restartError
	switch {
	case confirmed.Load() || errors.As(err, &re):
		return err
	case err != nil:
		return rollback(cfg, st, err, log)
	default:
		// Stopped on purpose before the check finished: the next start
		// is still the first try.
		st.Starts = 0
		update.Save(cfg.DataDir, st)
		return nil
	}
}

type trialRun struct {
	update.State
	confirmed *atomic.Bool
}

func adminCommand(cfg config.Config) error {
	db, err := openStore(cfg)
	if err != nil {
		return err
	}
	defer db.Close()
	if len(cfg.Args) > 1 && cfg.Args[1] == "update" {
		return updateCommand(context.Background(), cfg, db, cfg.Args[2:], os.Stdout)
	}
	files := &blob.Disk{Root: filepath.Join(cfg.DataDir, "files"), MaxSize: cfg.MaxUploadMB << 20}
	return admin.RunWith(context.Background(), db, admin.Options{Files: files, OpenDatabase: openTarget}, cfg.Args[1:], os.Stdout)
}

func serve(cfg config.Config, log *slog.Logger, trial *trialRun) error {
	db, err := openStore(cfg)
	if err != nil {
		return err
	}
	defer db.Close()
	log.Info("database", "engine", db.Dialect())
	files := &blob.Disk{Root: filepath.Join(cfg.DataDir, "files"), MaxSize: cfg.MaxUploadMB << 20}

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
	var metricsSrv *http.Server
	if cfg.MetricsAddr != "" {
		// Before Handler, so request counting is on from the first request.
		metricsSrv = &http.Server{Addr: cfg.MetricsAddr, Handler: srv.MetricsHandler(), ReadHeaderTimeout: 10 * time.Second}
	}
	httpSrv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if sender != nil {
		go srv.WatchOutings(ctx, time.Minute)
	}
	if cfg.BackupDir != "" {
		if db.Dialect() != "sqlite" {
			log.Warn("scheduled backups only cover SQLite; use pg_dump for PostgreSQL", "engine", db.Dialect())
		} else {
			log.Info("scheduled backups", "dir", cfg.BackupDir, "every", cfg.BackupEvery, "keep", cfg.BackupKeep)
			go backup.Run(ctx, db, backup.Options{Dir: cfg.BackupDir, Every: cfg.BackupEvery, Keep: cfg.BackupKeep, Files: files.Root, Log: log})
		}
	}
	errc := make(chan error, 4)
	restart := make(chan string, 1)
	checker := &update.Checker{URL: cfg.UpdateURL}
	if cfg.UpdateCheck {
		go watchReleases(ctx, checker, log)
	}
	if cfg.AdminAddr != "" {
		token, err := adminToken(cfg, log)
		if err != nil {
			return fmt.Errorf("admin token: %w", err)
		}
		self, err := program()
		if err != nil {
			return err
		}
		opts := console.Options{
			Version: version, Engine: db.Dialect(), DataDir: cfg.DataDir, Token: token,
			Checker: checker, Docker: update.InDocker(), Log: log,
			Restart: func() {
				select {
				case restart <- self:
				default:
				}
			},
		}
		if !opts.Docker {
			opts.Apply = installer(cfg, db, self)
		}
		adminSrv := &http.Server{Addr: cfg.AdminAddr, Handler: console.Handler(opts), ReadHeaderTimeout: 10 * time.Second}
		go func() {
			log.Info("operator console listening", "addr", cfg.AdminAddr)
			if err := adminSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				errc <- fmt.Errorf("admin console: %w", err)
			}
		}()
		defer adminSrv.Close()
	}
	if metricsSrv != nil {
		go func() {
			log.Info("metrics listening", "addr", cfg.MetricsAddr)
			if err := metricsSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				errc <- fmt.Errorf("metrics: %w", err)
			}
		}()
		defer metricsSrv.Close()
	}
	go func() {
		log.Info("geocrew-server listening", "addr", cfg.Addr, "protocol", api.ProtocolVersion,
			"version", version, "data", cfg.DataDir)
		errc <- httpSrv.ListenAndServe()
	}()
	if trial != nil {
		tctx, cancel := context.WithCancel(ctx)
		defer cancel()
		go func() {
			if confirmTrial(tctx, cfg, trial.State, log, errc) {
				trial.confirmed.Store(true)
			}
		}()
	}
	var next string
	select {
	case err := <-errc:
		return err
	case next = <-restart:
		log.Info("restarting into the installed version")
	case <-ctx.Done():
	}
	log.Info("shutting down")
	sctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpSrv.Shutdown(sctx); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	if next != "" {
		return restartError{next}
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

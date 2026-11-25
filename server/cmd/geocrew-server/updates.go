package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/config"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqldb"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/update"
)

// restartError asks main to start bin in place of this process once
// everything has shut down.
type restartError struct{ bin string }

func (e restartError) Error() string { return "restart into " + e.bin }

// program is the running program's file, with symlinks resolved, since
// that is the file an update replaces. Read it at start: once the file is
// renamed, the kernel reports the new name.
func program() (string, error) {
	p, err := os.Executable()
	if err != nil {
		return "", err
	}
	return filepath.EvalSymlinks(p)
}

// installer returns what installs a release over the running program:
// download and check it, snapshot SQLite, swap the files, record a trial.
func installer(cfg config.Config, db *sqldb.DB, bin string) func(ctx context.Context, r update.Release) (update.State, error) {
	return func(ctx context.Context, r update.Release) (update.State, error) {
		tmp, err := update.Download(ctx, nil, r, runtime.GOOS, runtime.GOARCH, bin)
		if err != nil {
			return update.State{}, err
		}
		o := update.InstallOptions{DataDir: cfg.DataDir, Binary: bin, New: tmp, From: version, To: r.Version}
		if db.Dialect() == "sqlite" {
			o.Database = filepath.Join(cfg.DataDir, "geocrew.db")
			o.Snapshot = func(dest string) error { return db.Backup(ctx, dest) }
			o.Check = func(newBin, snap string) error { return selfCheck(ctx, newBin, snap) }
		}
		st, err := update.Install(o)
		if err != nil {
			os.Remove(tmp)
		}
		return st, err
	}
}

// adminToken is the console token: the configured one, or one kept in the
// data directory (made on first use, readable by the server's user only).
func adminToken(cfg config.Config, log *slog.Logger) (string, error) {
	if cfg.AdminToken != "" {
		return cfg.AdminToken, nil
	}
	path := filepath.Join(cfg.DataDir, "admin-token")
	if b, err := os.ReadFile(path); err == nil {
		if t := strings.TrimSpace(string(b)); len(t) >= 16 {
			return t, nil
		}
	}
	raw := make([]byte, 24)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	t := hex.EncodeToString(raw)
	if err := os.WriteFile(path, []byte(t+"\n"), 0o600); err != nil {
		return "", err
	}
	log.Info("operator console token written; read it with: cat "+path, "file", path)
	return t, nil
}

// watchReleases logs once a day when a newer release is out.
func watchReleases(ctx context.Context, c *update.Checker, log *slog.Logger) {
	t := time.NewTimer(2 * time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		cctx, cancel := context.WithTimeout(ctx, 30*time.Second)
		r, err := c.Latest(cctx)
		cancel()
		switch {
		case err != nil:
			// Servers without internet access are normal; stay quiet.
			log.Debug("update check failed", "err", err)
		case update.Newer(r.Version, version):
			log.Info("a newer GeoCrew Server is out", "current", version, "latest", r.Version, "notes", r.URL)
		}
		t.Reset(24 * time.Hour)
	}
}

// confirmTrial probes the server's own health check after an update and
// marks the update done, or reports a failure on errc so it is rolled back.
func confirmTrial(ctx context.Context, cfg config.Config, st update.State, log *slog.Logger, errc chan<- error) bool {
	host, port, err := net.SplitHostPort(cfg.Addr)
	if err != nil {
		errc <- fmt.Errorf("listen address: %w", err)
		return false
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	url := "http://" + net.JoinHostPort(host, port) + "/healthz"
	client := &http.Client{Timeout: 3 * time.Second}
	deadline := time.Now().Add(60 * time.Second)
	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return false
		case <-time.After(time.Second):
		}
		resp, err := client.Get(url)
		if err != nil {
			continue
		}
		io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		if resp.StatusCode == http.StatusOK {
			if err := update.Confirm(cfg.DataDir, st); err != nil {
				log.Error("could not record the finished update", "err", err)
			}
			log.Info("update finished", "from", st.From, "to", st.To)
			return true
		}
	}
	errc <- errors.New("新版本 60 秒内没有通过健康检查")
	return false
}

// rollback puts the old program and database back after a failed trial and
// returns the restart into it.
func rollback(cfg config.Config, st update.State, reason error, log *slog.Logger) error {
	log.Error("updated version failed, rolling back", "from", st.To, "to", st.From, "err", reason)
	st, err := update.Rollback(cfg.DataDir, st, reason.Error())
	if err != nil {
		return fmt.Errorf("新版本失败（%v），退回也失败：%w", reason, err)
	}
	if !update.CanRestart {
		return fmt.Errorf("新版本失败（%v），已退回 %s，请重启服务", reason, st.From)
	}
	return restartError{st.Binary}
}

// updateCommand is "admin update [check|apply|rollback]".
func updateCommand(ctx context.Context, cfg config.Config, db *sqldb.DB, args []string, out io.Writer) error {
	sub := "check"
	if len(args) > 0 {
		sub = args[0]
	}
	checker := &update.Checker{URL: cfg.UpdateURL}
	switch sub {
	case "check", "apply":
		r, err := checker.Latest(ctx)
		if err != nil {
			return err
		}
		fmt.Fprintf(out, "当前版本 %s，最新版本 %s（%s）\n", version, r.Version, r.PublishedAt.Local().Format("2006-01-02 15:04"))
		if r.Notes != "" {
			fmt.Fprintf(out, "\n%s\n\n", strings.TrimSpace(r.Notes))
		}
		if !update.Newer(r.Version, version) {
			if !update.IsRelease(version) {
				fmt.Fprintln(out, "这是开发版本，不会被替换。")
			} else {
				fmt.Fprintln(out, "已是最新版本。")
			}
			return nil
		}
		if sub == "check" {
			if update.InDocker() {
				fmt.Fprintln(out, "在 Docker 里请更新镜像：docker compose pull && docker compose up -d")
			} else {
				fmt.Fprintln(out, "安装：geocrew-server admin update apply")
			}
			return nil
		}
		if update.InDocker() {
			return errors.New("在 Docker 里请更新镜像：docker compose pull && docker compose up -d")
		}
		if db.Dialect() != "sqlite" {
			fmt.Fprintln(out, "PostgreSQL 不会自动备份，请确认已用 pg_dump 备份。")
		}
		bin, err := program()
		if err != nil {
			return err
		}
		st, err := installer(cfg, db, bin)(ctx, r)
		if err != nil {
			return err
		}
		fmt.Fprintf(out, "已安装 %s，旧程序留在 %s。", st.To, st.Previous)
		if st.Snapshot != "" {
			fmt.Fprintf(out, "更新前的数据库在 %s。", st.Snapshot)
		}
		fmt.Fprintln(out, "\n请重启服务（如 systemctl restart geocrew-server）。新版本启动后通过健康检查才算成功，否则自动退回。")
		return nil
	case "rollback":
		st, ok, err := update.Load(cfg.DataDir)
		if err != nil {
			return err
		}
		if !ok {
			return errors.New("还没有更新过")
		}
		if st, err = update.RestorePrevious(cfg.DataDir, st); err != nil {
			return err
		}
		fmt.Fprintf(out, "已换回 %s 的程序，数据库没有动。请重启服务。\n", st.To)
		return nil
	case "status":
		st, ok, err := update.Load(cfg.DataDir)
		if err != nil {
			return err
		}
		if !ok {
			fmt.Fprintf(out, "当前版本 %s，还没有更新过\n", version)
			return nil
		}
		fmt.Fprintf(out, "当前版本 %s；上次更新 %s → %s，%s，%s %s\n", version, st.From, st.To, st.At.Local().Format("2006-01-02 15:04"), st.Status, st.Error)
		return nil
	}
	return fmt.Errorf("admin update 的子命令是 check、apply、rollback 或 status")
}

// selfCheck runs the new program's migrations on a throwaway copy of the
// snapshot, so a release that cannot open this database is never switched to.
func selfCheck(ctx context.Context, bin, snapshot string) error {
	dir, err := os.MkdirTemp(filepath.Dir(snapshot), "selfcheck-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	tryDB := filepath.Join(dir, "geocrew.db")
	if err := update.CopyFile(snapshot, tryDB); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	out, err := exec.CommandContext(ctx, bin, "-selfcheck", tryDB).CombinedOutput()
	if err != nil || !strings.Contains(string(out), "selfcheck ok") {
		return fmt.Errorf("%v %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

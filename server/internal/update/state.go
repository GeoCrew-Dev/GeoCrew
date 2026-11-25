package update

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Status of the last update.
const (
	// StatusTrial: installed, the new program has not passed its health check yet.
	StatusTrial = "trial"
	// StatusDone: the new program started and answered its health check.
	StatusDone = "done"
	// StatusRolledBack: the new program failed and the old one is back.
	StatusRolledBack = "rolled_back"
)

// State records the last update in the data directory.
type State struct {
	From   string `json:"from"`
	To     string `json:"to"`
	Status string `json:"status"`
	// Binary is the program file that was replaced; Previous holds the old program.
	Binary   string `json:"binary"`
	Previous string `json:"previous"`
	// Snapshot is a copy of the SQLite database taken just before the
	// update, and Database the file it belongs to. Both are empty for
	// PostgreSQL, which the operator backs up with pg_dump.
	Snapshot string `json:"snapshot,omitempty"`
	Database string `json:"database,omitempty"`
	// Starts counts starts of the new program during its trial.
	Starts int       `json:"starts"`
	At     time.Time `json:"at"`
	Error  string    `json:"error,omitempty"`
}

const stateName = "update.json"

// Dir is where update snapshots and the state live inside the data directory.
func Dir(dataDir string) string { return filepath.Join(dataDir, "update") }

// Load reads the last update's state; ok is false when there has been none.
func Load(dataDir string) (st State, ok bool, err error) {
	b, err := os.ReadFile(filepath.Join(Dir(dataDir), stateName))
	if errors.Is(err, os.ErrNotExist) {
		return State{}, false, nil
	}
	if err != nil {
		return State{}, false, err
	}
	if err := json.Unmarshal(b, &st); err != nil {
		return State{}, false, fmt.Errorf("%s: %w", stateName, err)
	}
	return st, true, nil
}

// Save writes st atomically.
func Save(dataDir string, st State) error {
	dir := Dir(dataDir)
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return err
	}
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	tmp := filepath.Join(dir, "."+stateName)
	if err := os.WriteFile(tmp, b, 0o640); err != nil {
		return err
	}
	return os.Rename(tmp, filepath.Join(dir, stateName))
}

// InstallOptions describes one installation.
type InstallOptions struct {
	DataDir string
	// Binary is the running program's file; New is the checked download.
	Binary, New string
	From, To    string
	// Snapshot writes a consistent copy of the database to dest; nil when
	// the database cannot be copied here (PostgreSQL).
	Snapshot func(dest string) error
	// Database is the SQLite file Snapshot copies.
	Database string
	// Check, when set, tries the new program on the snapshot (e.g. runs its
	// migrations on a copy) before anything is switched.
	Check func(newBinary, snapshot string) error
	Now   func() time.Time
}

// Install snapshots the database, keeps the old program as Binary.prev,
// moves the new one into place and records a trial. The running process
// is unchanged until it restarts.
func Install(o InstallOptions) (State, error) {
	now := time.Now
	if o.Now != nil {
		now = o.Now
	}
	st := State{From: o.From, To: o.To, Status: StatusTrial, Binary: o.Binary, Previous: o.Binary + ".prev", At: now().UTC()}
	if o.Snapshot != nil {
		if err := os.MkdirAll(Dir(o.DataDir), 0o750); err != nil {
			return st, err
		}
		snap := filepath.Join(Dir(o.DataDir), "before-"+safe(o.To)+"-"+st.At.Format("20060102-150405")+".db")
		part := snap + ".partial"
		os.Remove(part)
		if err := o.Snapshot(part); err != nil {
			os.Remove(part)
			return st, fmt.Errorf("更新前备份数据库失败，没有更新：%w", err)
		}
		if err := os.Rename(part, snap); err != nil {
			return st, err
		}
		st.Snapshot, st.Database = snap, o.Database
		pruneSnapshots(Dir(o.DataDir), 3)
		if o.Check != nil {
			if err := o.Check(o.New, snap); err != nil {
				return st, fmt.Errorf("新程序在数据库副本上试运行失败，没有更新：%w", err)
			}
		}
	}
	os.Remove(st.Previous)
	if err := os.Rename(o.Binary, st.Previous); err != nil {
		return st, fmt.Errorf("无法保留旧程序，没有更新：%w", err)
	}
	if err := os.Rename(o.New, o.Binary); err != nil {
		if rerr := os.Rename(st.Previous, o.Binary); rerr != nil {
			return st, fmt.Errorf("替换程序失败（%v），旧程序在 %s，请手动改回：%w", err, st.Previous, rerr)
		}
		return st, fmt.Errorf("替换程序失败，没有更新：%w", err)
	}
	if err := Save(o.DataDir, st); err != nil {
		return st, err
	}
	return st, nil
}

// Begin is called first when the server starts. It returns the trial in
// progress, if any, counting this start. rollback is true when an earlier
// start of the same trial never passed its health check (it crashed or was
// killed): the caller should Rollback before touching the database.
func Begin(dataDir string, log *slog.Logger) (st State, trial, rollback bool, err error) {
	st, ok, err := Load(dataDir)
	if err != nil || !ok || st.Status != StatusTrial {
		return st, false, false, err
	}
	st.Starts++
	if err := Save(dataDir, st); err != nil {
		return st, true, false, err
	}
	if st.Starts > 1 {
		return st, true, true, nil
	}
	if log != nil {
		log.Info("trying the updated version; it is rolled back if it does not become healthy", "from", st.From, "to", st.To)
	}
	return st, true, false, nil
}

// Confirm marks the trial as passed.
func Confirm(dataDir string, st State) error {
	st.Status = StatusDone
	st.Error = ""
	return Save(dataDir, st)
}

// Rollback puts the database snapshot and the old program back and records
// why. The caller then starts the old program (Exec).
func Rollback(dataDir string, st State, reason string) (State, error) {
	var errs []error
	if st.Snapshot != "" && st.Database != "" {
		if err := restore(st.Snapshot, st.Database); err != nil {
			errs = append(errs, fmt.Errorf("恢复数据库：%w", err))
		}
	}
	if _, err := os.Stat(st.Previous); err != nil {
		errs = append(errs, fmt.Errorf("旧程序 %s 不在了：%w", st.Previous, err))
	} else {
		failed := st.Binary + ".failed"
		os.Remove(failed)
		if err := os.Rename(st.Binary, failed); err != nil && !errors.Is(err, os.ErrNotExist) {
			errs = append(errs, err)
		} else if err := os.Rename(st.Previous, st.Binary); err != nil {
			errs = append(errs, err)
		}
	}
	st.Status = StatusRolledBack
	st.Error = reason
	if err := Save(dataDir, st); err != nil {
		errs = append(errs, err)
	}
	return st, errors.Join(errs...)
}

// RestorePrevious swaps Binary.prev back in on the operator's request,
// after a finished update. The database is left as it is.
func RestorePrevious(dataDir string, st State) (State, error) {
	if st.Status != StatusDone {
		return st, errors.New("没有已完成的更新可以退回")
	}
	if _, err := os.Stat(st.Previous); err != nil {
		return st, fmt.Errorf("旧程序 %s 不在了", st.Previous)
	}
	tmp := st.Binary + ".swap"
	if err := os.Rename(st.Binary, tmp); err != nil {
		return st, err
	}
	if err := os.Rename(st.Previous, st.Binary); err != nil {
		os.Rename(tmp, st.Binary)
		return st, err
	}
	if err := os.Rename(tmp, st.Previous); err != nil {
		return st, err
	}
	st.From, st.To = st.To, st.From
	st.Error = "按管理员要求退回旧版本（数据库未恢复）"
	st.Status = StatusRolledBack
	return st, Save(dataDir, st)
}

// CopyFile copies from to a new file to.
func CopyFile(from, to string) error { return restore(from, to) }

func restore(snapshot, db string) error {
	in, err := os.Open(snapshot)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := db + ".restore"
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o640)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	// The write-ahead log belongs to the database being replaced.
	for _, s := range []string{"-wal", "-shm"} {
		if err := os.Remove(db + s); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
	}
	return os.Rename(tmp, db)
}

func pruneSnapshots(dir string, keep int) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	var snaps []string
	for _, e := range entries {
		if n := e.Name(); strings.HasPrefix(n, "before-") && strings.HasSuffix(n, ".db") {
			snaps = append(snaps, n)
		}
	}
	sort.Slice(snaps, func(i, j int) bool { return stamp(snaps[i]) < stamp(snaps[j]) })
	for len(snaps) > keep {
		os.Remove(filepath.Join(dir, snaps[0]))
		snaps = snaps[1:]
	}
}

// stamp is the time part of a snapshot name, which sorts by time.
func stamp(name string) string {
	s := strings.TrimSuffix(name, ".db")
	if len(s) < 15 {
		return s
	}
	return s[len(s)-15:]
}

func safe(v string) string {
	return strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r == '.' || r == '-' {
			return r
		}
		return '_'
	}, v)
}

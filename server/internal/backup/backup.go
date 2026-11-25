// Package backup takes scheduled copies of a running server: a consistent
// snapshot of the database and a mirror of the attachment files, keeping
// the newest few snapshots.
//
// Attachments are stored by content hash and never change, so the mirror
// only adds what is new and each run is cheap. Files removed from the
// server later (admin prune) stay in the mirror, because older snapshots
// may still refer to them.
package backup

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// Options says where and how often to back up.
type Options struct {
	// Dir receives geocrew-YYYYMMDD-HHMMSS.db snapshots and a files/ mirror.
	Dir string
	// Every is the time between runs; the first run comes shortly after start.
	Every time.Duration
	// Keep is how many snapshots to keep, newest first; at least 1.
	Keep int
	// Files is the attachment root to mirror; empty skips attachments.
	Files string
	Log   *slog.Logger
	// Now is the clock, for tests; nil uses time.Now.
	Now func() time.Time
}

const (
	prefix = "geocrew-"
	suffix = ".db"
	layout = "20060102-150405"
	// startDelay lets the server settle before the first copy.
	startDelay = time.Minute
)

// Run backs up every o.Every until ctx ends. Failures are logged and the
// next run tries again.
func Run(ctx context.Context, st store.Backuper, o Options) {
	log := o.logger()
	t := time.NewTimer(startDelay)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		name, err := Once(ctx, st, o)
		if err != nil {
			if ctx.Err() == nil {
				log.Error("backup failed", "dir", o.Dir, "err", err)
			}
		} else {
			log.Info("backup written", "file", name)
		}
		t.Reset(o.Every)
	}
}

// Once writes one snapshot, mirrors new attachments and drops old
// snapshots, returning the snapshot's path.
func Once(ctx context.Context, st store.Backuper, o Options) (string, error) {
	if err := os.MkdirAll(o.Dir, 0o750); err != nil {
		return "", err
	}
	now := time.Now
	if o.Now != nil {
		now = o.Now
	}
	name := filepath.Join(o.Dir, prefix+now().UTC().Format(layout)+suffix)
	if _, err := os.Stat(name); err == nil {
		return "", fmt.Errorf("%s already exists", name)
	}
	// Write under a temporary name so a crash never leaves a half snapshot
	// that looks complete.
	part := name + ".partial"
	os.Remove(part)
	if err := st.Backup(ctx, part); err != nil {
		os.Remove(part)
		return "", err
	}
	if err := os.Rename(part, name); err != nil {
		return "", err
	}
	if o.Files != "" {
		if err := Mirror(o.Files, filepath.Join(o.Dir, "files")); err != nil {
			return name, fmt.Errorf("snapshot written, attachments not mirrored: %w", err)
		}
	}
	if err := prune(o.Dir, o.Keep); err != nil {
		return name, fmt.Errorf("snapshot written, old ones not removed: %w", err)
	}
	return name, nil
}

// SnapshotTime reads when a snapshot was taken from its file name.
func SnapshotTime(path string) (time.Time, error) {
	n := filepath.Base(path)
	return time.Parse(layout, strings.TrimSuffix(strings.TrimPrefix(n, prefix), suffix))
}

// Snapshots lists the snapshots in dir, oldest first.
func Snapshots(dir string) ([]string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	var out []string
	for _, e := range entries {
		n := e.Name()
		if e.Type().IsRegular() && strings.HasPrefix(n, prefix) && strings.HasSuffix(n, suffix) {
			if _, err := time.Parse(layout, strings.TrimSuffix(strings.TrimPrefix(n, prefix), suffix)); err == nil {
				out = append(out, filepath.Join(dir, n))
			}
		}
	}
	sort.Strings(out) // the timestamp sorts by name
	return out, nil
}

func prune(dir string, keep int) error {
	if keep < 1 {
		keep = 1
	}
	all, err := Snapshots(dir)
	if err != nil {
		return err
	}
	var errs []error
	for len(all) > keep {
		if err := os.Remove(all[0]); err != nil {
			errs = append(errs, err)
		}
		all = all[1:]
	}
	return errors.Join(errs...)
}

// Mirror copies every file under src that dst lacks, keeping the relative
// paths. Files that exist in dst with the same size are taken as copied:
// attachment files are named by their hash and never change.
func Mirror(src, dst string) error {
	return filepath.WalkDir(src, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) && path == src {
				return filepath.SkipAll // nothing uploaded yet
			}
			return err
		}
		if !d.Type().IsRegular() || strings.HasPrefix(d.Name(), ".") { // uploads in progress
			return nil
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		target := filepath.Join(dst, rel)
		info, err := d.Info()
		if err != nil {
			return err
		}
		if got, err := os.Stat(target); err == nil && got.Size() == info.Size() {
			return nil
		}
		return copyFile(path, target)
	})
}

func copyFile(from, to string) error {
	if err := os.MkdirAll(filepath.Dir(to), 0o750); err != nil {
		return err
	}
	in, err := os.Open(from)
	if err != nil {
		return err
	}
	defer in.Close()
	part := to + ".partial"
	out, err := os.OpenFile(part, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o640)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(part)
		return err
	}
	if err := out.Close(); err != nil {
		os.Remove(part)
		return err
	}
	return os.Rename(part, to)
}

func (o Options) logger() *slog.Logger {
	if o.Log != nil {
		return o.Log
	}
	return slog.Default()
}

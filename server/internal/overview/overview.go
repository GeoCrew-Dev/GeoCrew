// Package overview sums up what a server holds, for the operator console:
// each workspace's members, records, attachments and last activity, the
// scheduled backups and the free disk space.
package overview

import (
	"context"
	"os"
	"path/filepath"
	"sort"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/backup"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
)

// Workspace is one row of the overview.
type Workspace struct {
	ID       string    `json:"id"`
	Name     string    `json:"name"`
	Created  time.Time `json:"created_at"`
	Archived bool      `json:"archived,omitempty"`
	Members  int       `json:"members"`
	Leaders  []string  `json:"leaders"`
	// Records counts features that are not deleted; Changes is every
	// version ever pushed, which is what the database grows with.
	Records   int   `json:"records"`
	Changes   int64 `json:"changes"`
	Files     int   `json:"files"`
	FileBytes int64 `json:"file_bytes"`
	// LastActive is the latest of the members' last requests and the
	// latest record change; zero if nothing has happened yet.
	LastActive time.Time `json:"last_active,omitempty"`
}

// Backup is one scheduled snapshot.
type Backup struct {
	Name  string    `json:"name"`
	At    time.Time `json:"at"`
	Bytes int64     `json:"bytes"`
}

// Overview is the whole summary.
type Overview struct {
	Workspaces []Workspace `json:"workspaces"`
	Members    int         `json:"members"`
	Records    int         `json:"records"`
	FileBytes  int64       `json:"file_bytes"`
	// DatabaseBytes is the SQLite file with its journal; zero for PostgreSQL.
	DatabaseBytes int64 `json:"database_bytes,omitempty"`
	// BackupDir is empty when scheduled backups are off.
	BackupDir string   `json:"backup_dir,omitempty"`
	Backups   []Backup `json:"backups,omitempty"`
	// FreeBytes is the free space where the data lives; -1 if unknown.
	FreeBytes int64     `json:"free_bytes"`
	At        time.Time `json:"at"`
}

// Options says where things are.
type Options struct {
	Files     *blob.Disk
	DataDir   string
	Database  string // the SQLite file, or "" for PostgreSQL
	BackupDir string
	Now       func() time.Time
}

const page = 1000

// Build reads the summary. It walks every workspace's records and files, so
// callers should cache it rather than build it on every request.
func Build(ctx context.Context, st store.Store, o Options) (Overview, error) {
	now := time.Now
	if o.Now != nil {
		now = o.Now
	}
	out := Overview{BackupDir: o.BackupDir, FreeBytes: -1, At: now()}
	all, err := st.Workspaces(ctx)
	if err != nil {
		return out, err
	}
	for _, ws := range all {
		row := Workspace{ID: ws.ID, Name: ws.Name, Created: ws.CreatedAt, Archived: ws.ArchivedAt != nil, Changes: ws.LastSeq, Leaders: []string{}}
		ms, err := st.Members(ctx, ws.ID)
		if err != nil {
			return out, err
		}
		for _, m := range ms {
			if m.Revoked {
				continue
			}
			row.Members++
			if m.Role == store.RoleLeader {
				row.Leaders = append(row.Leaders, m.Nickname)
			}
			if m.LastSeen != nil && m.LastSeen.After(row.LastActive) {
				row.LastActive = *m.LastSeen
			}
		}
		var since int64
		for {
			fs, more, err := st.PullFeatures(ctx, ws.ID, since, page)
			if err != nil {
				return out, err
			}
			for _, f := range fs {
				since = f.Seq
				if f.UpdatedAt.After(row.LastActive) && !f.UpdatedAt.After(out.At) {
					row.LastActive = f.UpdatedAt
				}
				if !f.Deleted {
					row.Records++
				}
			}
			if !more || len(fs) == 0 {
				break
			}
		}
		if o.Files != nil {
			stored, err := o.Files.List(ws.ID)
			if err != nil {
				return out, err
			}
			row.Files = len(stored)
			for _, f := range stored {
				row.FileBytes += f.Size
			}
		}
		out.Members += row.Members
		out.Records += row.Records
		out.FileBytes += row.FileBytes
		out.Workspaces = append(out.Workspaces, row)
	}
	// Most recently active first, so the page shows what is in use.
	sort.SliceStable(out.Workspaces, func(i, j int) bool {
		return out.Workspaces[i].LastActive.After(out.Workspaces[j].LastActive)
	})
	if o.Database != "" {
		for _, s := range []string{"", "-wal", "-shm"} {
			if fi, err := os.Stat(o.Database + s); err == nil {
				out.DatabaseBytes += fi.Size()
			}
		}
	}
	if o.BackupDir != "" {
		names, err := backup.Snapshots(o.BackupDir)
		if err == nil {
			for i := len(names) - 1; i >= 0; i-- { // newest first
				fi, err := os.Stat(names[i])
				if err != nil {
					continue
				}
				at, _ := backup.SnapshotTime(names[i])
				out.Backups = append(out.Backups, Backup{Name: filepath.Base(names[i]), At: at, Bytes: fi.Size()})
			}
		}
	}
	if o.DataDir != "" {
		out.FreeBytes = freeBytes(o.DataDir)
	}
	return out, nil
}

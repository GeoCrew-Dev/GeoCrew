package export

import (
	"archive/zip"
	"compress/flate"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// Format and version of the project package, shared with the Android app's
// ProjectPackage so a downloaded workspace opens there as a new project.
const (
	PackageFormat  = "geocrew-project"
	PackageVersion = 1
)

type packageConfig struct {
	Description string          `json:"description,omitempty"`
	Fields      json.RawMessage `json:"fields,omitempty"`
}

type manifest struct {
	Format     string        `json:"format"`
	Version    int           `json:"version"`
	Name       string        `json:"name"`
	CreatedAt  string        `json:"created_at"`
	ExportedAt string        `json:"exported_at"`
	Config     packageConfig `json:"config"`
	Generator  string        `json:"generator,omitempty"`
}

// PackageSummary says what went into a package.
type PackageSummary struct {
	Features int
	Photos   int
	// Missing counts photos the records refer to that are not on this server.
	Missing int
}

// OpenFile returns a stored attachment; errors satisfying errors.Is(err,
// os.ErrNotExist) mean the file was never uploaded.
type OpenFile func(sha string) (io.ReadCloser, error)

// Package writes the workspace as a project package (a zip):
//
//	project.json          name, dates and the shared record form
//	features.geojson      every live record
//	photos/<sha256>.jpg   attachments the records refer to under "photos"
//
// Workspace credentials, members and history are never included. The
// Android app imports the file as a new, unlinked project.
func Package(ctx context.Context, st store.Store, workspaceID string, open OpenFile, generator string, now time.Time, out io.Writer) (PackageSummary, error) {
	var sum PackageSummary
	ws, err := st.Workspace(ctx, workspaceID)
	if err != nil {
		return sum, err
	}
	m := manifest{
		Format:     PackageFormat,
		Version:    PackageVersion,
		Name:       ws.Name,
		CreatedAt:  ws.CreatedAt.UTC().Format(time.RFC3339),
		ExportedAt: now.UTC().Format(time.RFC3339),
		Config:     packageConfig{Description: "从工作组「" + ws.Name + "」导出"},
		Generator:  generator,
	}
	if raw, _, err := st.Settings(ctx, workspaceID); err == nil {
		var s struct {
			Form json.RawMessage `json:"form"`
		}
		// A malformed or missing form just leaves the project without one.
		if json.Unmarshal(raw, &s) == nil && len(s.Form) > 0 && s.Form[0] == '[' {
			m.Config.Fields = s.Form
		}
	} else if !errors.Is(err, store.ErrNotFound) {
		return sum, err
	}

	z := zip.NewWriter(out)
	// Photos barely shrink, so favour speed. Entries are deflated, never
	// stored: a streamed STORED entry needs a data descriptor, which Java's
	// ZipInputStream (used by the app) refuses to read.
	z.RegisterCompressor(zip.Deflate, func(w io.Writer) (io.WriteCloser, error) {
		return flate.NewWriter(w, flate.BestSpeed)
	})
	entry := func(name string) (io.Writer, error) {
		return z.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Deflate, Modified: now})
	}
	head, err := entry("project.json")
	if err != nil {
		return sum, err
	}
	enc := json.NewEncoder(head)
	enc.SetIndent("", "  ")
	if err := enc.Encode(m); err != nil {
		return sum, err
	}

	body, err := entry("features.geojson")
	if err != nil {
		return sum, err
	}
	var photos []string
	known := map[string]bool{}
	sum.Features, err = writeCollection(ctx, st, workspaceID, body, func(f store.Feature) {
		for _, sha := range PhotoRefs(f.Properties) {
			if !known[sha] {
				known[sha] = true
				photos = append(photos, sha)
			}
		}
	})
	if err != nil {
		return sum, err
	}

	for _, sha := range photos {
		if err := ctx.Err(); err != nil {
			return sum, err
		}
		r, err := open(sha)
		if errors.Is(err, os.ErrNotExist) {
			sum.Missing++
			continue
		}
		if err != nil {
			return sum, err
		}
		w, err := entry("photos/" + sha + ".jpg")
		if err == nil {
			_, err = io.Copy(w, r)
		}
		r.Close()
		if err != nil {
			return sum, err
		}
		sum.Photos++
	}
	return sum, z.Close()
}

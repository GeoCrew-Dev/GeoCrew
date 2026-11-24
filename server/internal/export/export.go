// Package export writes a workspace's current data in standard formats so
// it can be taken into desktop GIS without a GeoCrew client.
package export

import (
	"bufio"
	"context"
	"encoding/json"
	"io"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

const page = 1000

type feature struct {
	Type       string          `json:"type"`
	ID         string          `json:"id"`
	Geometry   json.RawMessage `json:"geometry"`
	Properties json.RawMessage `json:"properties"`
}

// GeoJSON streams the workspace's live features (deleted ones left out) as
// one RFC 7946 FeatureCollection and returns how many were written. Sync
// metadata is dropped; properties are copied unchanged.
func GeoJSON(ctx context.Context, st store.Store, workspaceID string, out io.Writer) (int, error) {
	if _, err := st.Workspace(ctx, workspaceID); err != nil {
		return 0, err
	}
	return writeCollection(ctx, st, workspaceID, out, nil)
}

// writeCollection writes the live features as a FeatureCollection, calling
// seen (if not nil) for each one written.
func writeCollection(ctx context.Context, st store.Store, workspaceID string, out io.Writer, seen func(store.Feature)) (int, error) {
	w := bufio.NewWriter(out)
	if _, err := w.WriteString(`{"type":"FeatureCollection","features":[`); err != nil {
		return 0, err
	}
	n := 0
	err := eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		b, err := json.Marshal(feature{"Feature", f.ID, orNull(f.Geometry), orNull(f.Properties)})
		if err != nil {
			return err
		}
		if n > 0 {
			w.WriteByte(',')
		}
		w.WriteByte('\n')
		if _, err := w.Write(b); err != nil {
			return err
		}
		if seen != nil {
			seen(f)
		}
		n++
		return nil
	})
	if err != nil {
		return n, err
	}
	if _, err := w.WriteString("\n]}\n"); err != nil {
		return n, err
	}
	return n, w.Flush()
}

// eachLive calls fn for every feature that is not deleted, in seq order.
func eachLive(ctx context.Context, st store.Store, workspaceID string, fn func(store.Feature) error) error {
	var since int64
	for {
		fs, more, err := st.PullFeatures(ctx, workspaceID, since, page)
		if err != nil {
			return err
		}
		for _, f := range fs {
			since = f.Seq
			if f.Deleted {
				continue
			}
			if err := fn(f); err != nil {
				return err
			}
		}
		if !more || len(fs) == 0 {
			return nil
		}
	}
}

func orNull(b json.RawMessage) json.RawMessage {
	if len(b) == 0 {
		return json.RawMessage("null")
	}
	return b
}

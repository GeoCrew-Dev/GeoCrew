package export

import (
	"bytes"
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
)

func TestGeoJSONSkipsDeletedAndPages(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, err := db.CreateWorkspace(ctx, "组", "甲")
	if err != nil {
		t.Fatal(err)
	}
	at := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	var fs []store.Feature
	for i := 0; i < page+5; i++ {
		fs = append(fs, store.Feature{
			ID: "f" + string(rune('a'+i%26)) + string(rune('a'+i/26%26)) + string(rune('a'+i/676)), Version: 1, UpdatedAt: at,
			Geometry:   json.RawMessage(`{"type":"Point","coordinates":[116,40]}`),
			Properties: json.RawMessage(`{"title":"点"}`),
		})
	}
	fs[3].Deleted = true
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}

	var buf bytes.Buffer
	n, err := GeoJSON(ctx, db, ws.ID, &buf)
	if err != nil || n != page+4 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	var fc struct {
		Type     string `json:"type"`
		Features []struct {
			ID         string          `json:"id"`
			Properties json.RawMessage `json:"properties"`
			Sync       *struct{}       `json:"sync"`
		} `json:"features"`
	}
	if err := json.Unmarshal(buf.Bytes(), &fc); err != nil {
		t.Fatalf("invalid json: %v", err)
	}
	if fc.Type != "FeatureCollection" || len(fc.Features) != n || fc.Features[0].Sync != nil || string(fc.Features[0].Properties) != `{"title":"点"}` {
		t.Fatalf("unexpected output: %s", buf.String()[:200])
	}

	if _, err := GeoJSON(ctx, db, "nope", &buf); err != store.ErrNotFound {
		t.Fatalf("missing workspace: %v", err)
	}
}

func TestGeoJSONEmpty(t *testing.T) {
	ctx := context.Background()
	db, _ := sqlite.Open(":memory:")
	defer db.Close()
	ws, _, _, _ := db.CreateWorkspace(ctx, "组", "甲")
	var buf bytes.Buffer
	if n, err := GeoJSON(ctx, db, ws.ID, &buf); err != nil || n != 0 {
		t.Fatal(n, err)
	}
	var v map[string]any
	if err := json.Unmarshal(buf.Bytes(), &v); err != nil {
		t.Fatal(err)
	}
}

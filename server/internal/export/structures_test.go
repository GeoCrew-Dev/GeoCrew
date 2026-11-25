package export

import (
	"bytes"
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
)

func TestStructuresCSV(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, _ := db.CreateWorkspace(ctx, "组", "甲")
	at := time.Date(2026, 11, 25, 8, 0, 0, 0, time.UTC)
	fs := []store.Feature{
		{ID: "b", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"Point","coordinates":[110.5,30.25,812]}`),
			Properties: json.RawMessage(`{"kind":"geo_point","title":"D10","lithology":"灰岩","dip_direction":120,"dip":35,"trend":160,"plunge":30,"lineation_type":"擦痕"}`)},
		{ID: "a", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"LineString","coordinates":[[110,30],[110.1,30]]}`),
			Properties: json.RawMessage(`{"kind":"line","title":"D2","line_style":"fault","dip_direction":10,"dip":70}`)},
		{ID: "c", Version: 1, UpdatedAt: at, Properties: json.RawMessage(`{"kind":"point","title":"D3","dip":95,"dip_direction":10,"trend":20}`)},
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	n, err := Structures(ctx, db, ws.ID, &buf)
	if err != nil || n != 3 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	lines := strings.Split(strings.TrimSpace(strings.TrimPrefix(buf.String(), "\uFEFF")), "\r\n")
	want := []string{
		strings.Join(StructureHeader, ","),
		"D2,面状,实测断层,10.0,70.0,280.0,110.0000000,30.0000000,,,a",
		"D10,面状,,120.0,35.0,30.0,110.5000000,30.2500000,812.0,灰岩,b",
		"D10,线状,擦痕,160.0,30.0,,110.5000000,30.2500000,812.0,灰岩,b",
	}
	if strings.Join(lines, "\n") != strings.Join(want, "\n") {
		t.Fatalf("csv = %q", lines)
	}
}

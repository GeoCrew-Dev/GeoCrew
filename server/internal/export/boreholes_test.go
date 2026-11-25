package export

import (
	"bytes"
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store/sqlite"
)

func TestBoreholesCSV(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, _ := db.CreateWorkspace(ctx, "组", "甲")
	at := time.Date(2026, 11, 25, 8, 0, 0, 0, time.UTC)
	pt := json.RawMessage(`{"type":"Point","coordinates":[110.5,30.25,812.34]}`)
	fs := []store.Feature{
		{ID: "b", Version: 1, UpdatedAt: at, Properties: json.RawMessage(`{"kind":"borehole","title":"ZK10","borehole":{"layers":[{"no":"1","bottom":3}]}}`)},
		{ID: "a", Version: 1, UpdatedAt: at, Geometry: pt, Properties: json.RawMessage(`{"kind":"borehole","title":"ZK2","borehole":{"depth":10,"water":6.4,"layers":[{"no":"1","bottom":1.2,"lithology":"素填土"},{"no":"x","bottom":1},{"no":"2","bottom":8.5,"lithology":"=粉质黏土","note":"可塑"}]}}`)},
		{ID: "c", Version: 1, UpdatedAt: at, Geometry: pt, Properties: json.RawMessage(`{"kind":"point","title":"ZK1","borehole":{"layers":[{"no":"1","bottom":1}]}}`)},
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	n, err := Boreholes(ctx, db, ws.ID, &buf)
	if err != nil || n != 3 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	lines := strings.Split(strings.TrimSpace(strings.TrimPrefix(buf.String(), "\uFEFF")), "\r\n")
	want := []string{
		strings.Join(BoreholeHeader, ","),
		"ZK2,110.5000000,30.2500000,812.3,10.00,6.40,1,0.00,1.20,1.20,素填土,,a",
		"ZK2,110.5000000,30.2500000,812.3,10.00,6.40,2,1.20,8.50,7.30,'=粉质黏土,可塑,a",
		"ZK10,,,,3.00,,1,0.00,3.00,3.00,,,b",
	}
	if strings.Join(lines, "\n") != strings.Join(want, "\n") {
		t.Fatalf("csv = %q", lines)
	}
}

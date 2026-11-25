package export

import (
	"bytes"
	"context"
	"encoding/csv"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store/sqlite"
)

func TestSamplesRegister(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, _ := db.CreateWorkspace(ctx, "组", "甲")
	at := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	fs := []store.Feature{
		{ID: "a", Version: 1, UpdatedAt: at,
			Geometry:   json.RawMessage(`{"type":"Point","coordinates":[116.5,39.9,52]}`),
			Properties: json.RawMessage(`{"title":"D10","lithology":"花岗岩","samples":[{"no":"D10-1","type":"薄片","taken_at":"2026-11-24T20:00:00Z","note":"=HYPERLINK(1)","results":{"Cu(ppm)":35.2,"Au(ppb)":"<0.5","bad":"x"}}]}`)},
		{ID: "b", Version: 1, UpdatedAt: at,
			Geometry:   json.RawMessage(`{"type":"LineString","coordinates":[[110,30],[111,31]]}`),
			Properties: json.RawMessage(`{"title":"D2","samples":[{"no":"D2-2"},{"type":"no number"},{"no":"D2-1","note":"-0.5 m","results":{"Cu(ppm)":7}}]}`)},
		{ID: "c", Version: 1, UpdatedAt: at, Deleted: true,
			Properties: json.RawMessage(`{"samples":[{"no":"X-1"}]}`)},
		{ID: "d", Version: 1, UpdatedAt: at, Properties: json.RawMessage(`{"title":"no samples"}`)},
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	n, err := Samples(ctx, db, ws.ID, time.FixedZone("CST", 8*3600), &buf)
	if err != nil || n != 3 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	text := buf.String()
	if !strings.HasPrefix(text, "\uFEFF") {
		t.Fatal("missing BOM")
	}
	rows, err := csv.NewReader(strings.NewReader(strings.TrimPrefix(text, "\uFEFF"))).ReadAll()
	if err != nil {
		t.Fatal(err)
	}
	want := [][]string{
		append(append([]string{}, SampleHeader...), "Au(ppb)", "Cu(ppm)"),
		{"D2-1", "", "D2", "110", "30", "", "", "", "-0.5 m", "b", "", "7"},
		{"D2-2", "", "D2", "110", "30", "", "", "", "", "b", "", ""},
		{"D10-1", "薄片", "D10", "116.5", "39.9", "52", "花岗岩", "2026-11-25 04:00", "'=HYPERLINK(1)", "a", "<0.5", "35.2"},
	}
	if len(rows) != len(want) {
		t.Fatalf("rows: %q", rows)
	}
	for i := range want {
		if strings.Join(rows[i], "|") != strings.Join(want[i], "|") {
			t.Fatalf("row %d: %q, want %q", i, rows[i], want[i])
		}
	}
}

func TestDefuseAndNaturalOrder(t *testing.T) {
	for in, out := range map[string]string{"=1+1": "'=1+1", "+a": "'+a", "@x": "'@x", "-5": "-5", "-x": "'-x", "D-1": "D-1", "": ""} {
		if got := defuse(in); got != out {
			t.Errorf("defuse(%q) = %q", in, got)
		}
	}
	if !naturalLess("D2-1", "D10-1") || naturalLess("D10-1", "D2-1") || !naturalLess("d2-1", "D2-2") || !naturalLess("D2", "D2-1") {
		t.Fatal("natural order")
	}
}

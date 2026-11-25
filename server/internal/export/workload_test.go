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

func TestWorkloadCSV(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, a, _, _ := db.CreateWorkspace(ctx, "组", "甲")
	_, b, _, err := db.Join(ctx, ws.InviteCode, "乙")
	if err != nil {
		t.Fatal(err)
	}
	day1 := time.Date(2026, 11, 24, 2, 0, 0, 0, time.UTC)
	// 17:00 UTC is already the 25th in Beijing.
	day2 := time.Date(2026, 11, 24, 17, 0, 0, 0, time.UTC)
	push := func(m string, fs ...store.Feature) {
		t.Helper()
		if _, _, err := db.PushFeatures(ctx, ws.ID, m, fs); err != nil {
			t.Fatal(err)
		}
	}
	push(a.ID,
		store.Feature{ID: "p1", Version: 1, UpdatedAt: day1, Properties: json.RawMessage(`{"kind":"geo_point","samples":[{"no":"S1","taken_at":"2026-11-24T02:10:00Z"},{"no":"S2","taken_at":"2026-11-24T17:30:00Z"}],"photo_info":{"x":{"taken_at":"2026-11-24T02:20:00Z"}}}`)},
		store.Feature{ID: "t1", Version: 1, UpdatedAt: day1, Geometry: json.RawMessage(`{"type":"LineString","coordinates":[[116,40],[116,40.01]]}`), Properties: json.RawMessage(`{"kind":"track","started_at":"2026-11-24T01:00:00Z"}`)},
	)
	push(b.ID, store.Feature{ID: "p2", Version: 1, UpdatedAt: day2, Properties: json.RawMessage(`{"kind":"point"}`)})
	// 乙 edits 甲's point on the 25th: an edit for 乙, still 甲's record.
	push(b.ID, store.Feature{ID: "p1", Version: 2, UpdatedAt: day2, Properties: json.RawMessage(`{"kind":"geo_point","title":"D1","samples":[{"no":"S1","taken_at":"2026-11-24T02:10:00Z"},{"no":"S2","taken_at":"2026-11-24T17:30:00Z"}],"photo_info":{"x":{"taken_at":"2026-11-24T02:20:00Z"}}}`)})

	var buf bytes.Buffer
	n, err := Workload(ctx, db, ws.ID, time.FixedZone("", 8*3600), &buf)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(strings.TrimPrefix(buf.String(), "\uFEFF")), "\r\n")
	want := []string{
		strings.Join(WorkloadHeader, ","),
		"2026-11-24,甲,1,1,0,1,1,1.11",
		"2026-11-25,乙,1,0,1,0,0,0.00",
		"2026-11-25,甲,0,0,0,1,0,0.00",
		"合计,乙,1,0,1,0,0,0.00",
		"合计,甲,1,1,0,2,1,1.11",
	}
	if strings.Join(lines, "\n") != strings.Join(want, "\n") || n != 5 {
		t.Fatalf("n=%d csv = %q", n, lines)
	}
}

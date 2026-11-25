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

func TestRoutesCSV(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, _ := db.CreateWorkspace(ctx, "组", "甲")
	at := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	pt := json.RawMessage(`{"type":"Point","coordinates":[116,40,1250]}`)
	fs := []store.Feature{
		{ID: "d1", Version: 1, UpdatedAt: at, Geometry: pt, Properties: json.RawMessage(`{"kind":"geo_point","title":"D1","lithology":"灰岩","dip_direction":125,"dip":42,"note":"=层理清楚"}`)},
		{ID: "d2", Version: 1, UpdatedAt: at, Geometry: pt, Properties: json.RawMessage(`{"kind":"point","title":"D2"}`)},
		{ID: "r2", Version: 1, UpdatedAt: at, Properties: json.RawMessage(`{"kind":"route","title":"L002","route":{"date":"2026-11-24","members":"王","purpose":"追索断层","stops":[{"feature":"d2","onward":"沿沟向东"},{"feature":"gone"},{"feature":"d2"},{"feature":"d1"}],"summary":"断层走向北东"}}`)},
		{ID: "r1", Version: 1, UpdatedAt: at, Properties: json.RawMessage(`{"kind":"route","title":"L001","route":{"date":"2026-11-23","stops":[{"feature":"d1"}]}}`)},
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	n, err := Routes(ctx, db, ws.ID, &buf)
	if err != nil || n != 5 {
		t.Fatalf("n=%d err=%v\n%s", n, err, buf.String())
	}
	lines := strings.Split(strings.TrimSpace(strings.TrimPrefix(buf.String(), "\uFEFF")), "\r\n")
	want := []string{
		strings.Join(RouteHeader, ","),
		"L001,2026-11-23,,1,D1,地质点,116.000000,40.000000,1250.0,灰岩,125°∠42°,'=层理清楚,,d1",
		"L002,2026-11-24,王,目的,,,,,,,,追索断层,,r2",
		"L002,2026-11-24,王,1,D2,标注点,116.000000,40.000000,1250.0,,,,沿沟向东,d2",
		"L002,2026-11-24,王,2,D1,地质点,116.000000,40.000000,1250.0,灰岩,125°∠42°,'=层理清楚,,d1",
		"L002,2026-11-24,王,小结,,,,,,,,断层走向北东,,r2",
	}
	if strings.Join(lines, "\n") != strings.Join(want, "\n") {
		t.Fatalf("csv =\n%s", strings.Join(lines, "\n"))
	}
}

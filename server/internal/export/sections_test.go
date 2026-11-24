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

func TestSectionRows(t *testing.T) {
	var legs, layers []map[string]any
	json.Unmarshal([]byte(`[{"azimuth":90,"slope":0,"distance":20},{"azimuth":"x","distance":5}]`), &legs)
	json.Unmarshal([]byte(`[{"no":"1","top":10,"lithology":"灰岩","dip_direction":90,"dip":30},{"no":"2","top":20,"lithology":"=页岩"},{"top":"bad"}]`), &layers)
	rows := sectionRows(legs, layers, "f1", "P1")
	if len(rows) != 2 {
		t.Fatalf("rows = %v", rows)
	}
	want := []string{"P1", "1", "0.00", "10.00", "灰岩", "90", "30", "实测", "5.00", "", "f1"}
	if strings.Join(rows[0], "|") != strings.Join(want, "|") {
		t.Errorf("row 1 = %v", rows[0])
	}
	if rows[1][7] != "借用" || rows[1][8] != "5.00" || rows[1][2] != "10.00" {
		t.Errorf("row 2 = %v", rows[1])
	}
	// No attitude anywhere: no thickness, no source.
	rows = sectionRows(legs, layers[1:2], "f1", "P1")
	if rows[0][7] != "" || rows[0][8] != "" {
		t.Errorf("without attitude = %v", rows[0])
	}
}

func TestSectionsCSV(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, _ := db.CreateWorkspace(ctx, "组", "甲")
	at := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	line := json.RawMessage(`{"type":"LineString","coordinates":[[116,40],[116.001,40]]}`)
	fs := []store.Feature{
		{ID: "b", Version: 1, UpdatedAt: at, Geometry: line, Properties: json.RawMessage(`{"kind":"section","title":"P10","section":{"legs":[{"azimuth":90,"slope":0,"distance":10}],"layers":[{"no":"1","top":10,"dip_direction":90,"dip":30}]}}`)},
		{ID: "a", Version: 1, UpdatedAt: at, Properties: json.RawMessage(`{"kind":"section","title":"P2","section":{"legs":[{"azimuth":90,"slope":0,"distance":10}],"layers":[{"no":"1","top":10,"lithology":"=1+1","dip_direction":90,"dip":30}]}}`)},
		{ID: "c", Version: 1, UpdatedAt: at, Geometry: line, Properties: json.RawMessage(`{"kind":"line","title":"P1","section":{"layers":[{"no":"1","top":1}]}}`)},
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	n, err := Sections(ctx, db, ws.ID, &buf)
	if err != nil || n != 2 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	lines := strings.Split(strings.TrimSpace(strings.TrimPrefix(buf.String(), "\uFEFF")), "\r\n")
	if len(lines) != 3 || !strings.HasPrefix(lines[1], "P2,1,0.00,10.00,'=1+1,") || !strings.HasPrefix(lines[2], "P10,") {
		t.Fatalf("csv = %q", lines)
	}
}

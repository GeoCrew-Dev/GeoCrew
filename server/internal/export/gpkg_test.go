package export

import (
	"context"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"math"
	"path/filepath"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
)

func TestGeoPackage(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, _ := db.CreateWorkspace(ctx, "北山组", "甲")
	at := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	fs := []store.Feature{
		{ID: "a", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"Point","coordinates":[116.5,40,52.3]}`),
			Properties: json.RawMessage(`{"kind":"geo_point","title":"D001","lithology":"灰岩","dip_direction":135,"dip":40}`)},
		{ID: "b", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"Polygon","coordinates":[[[116,40],[116.1,40],[116.1,40.1]]]}`),
			Properties: json.RawMessage(`{"kind":"polygon","fill":"#22a884"}`)},
		{ID: "c", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"MultiLineString","coordinates":[[[116,40],[116,41]],[[117,40],[117,41]]]}`),
			Properties: json.RawMessage(`{"kind":"line","line_style":"inferred_fault","dip":"steep"}`)},
		{ID: "d", Version: 1, UpdatedAt: at, Properties: json.RawMessage(`{"kind":"note"}`)},
		{ID: "e", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"LineString","coordinates":[[116,40]]}`), Properties: json.RawMessage(`{}`)},
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "x.gpkg")
	n, err := GeoPackage(ctx, db, ws.ID, path)
	if err != nil || n != 3 {
		t.Fatalf("n=%d err=%v", n, err)
	}

	g, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer g.Close()
	var app, ver int64
	g.QueryRow("PRAGMA application_id").Scan(&app)
	g.QueryRow("PRAGMA user_version").Scan(&ver)
	if app != 0x47504B47 || ver != 10300 {
		t.Fatalf("application_id=%x user_version=%d", app, ver)
	}
	rows, err := g.Query("SELECT table_name, identifier, srs_id, min_x, max_y FROM gpkg_contents ORDER BY table_name")
	if err != nil {
		t.Fatal(err)
	}
	var tables []string
	for rows.Next() {
		var name, id string
		var srs int
		var minX, maxY float64
		rows.Scan(&name, &id, &srs, &minX, &maxY)
		tables = append(tables, name+"/"+id)
		if srs != 4326 {
			t.Fatalf("%s srs %d", name, srs)
		}
		if name == "lines" && (minX != 116 || maxY != 41) {
			t.Fatalf("lines extent %v %v", minX, maxY)
		}
	}
	rows.Close()
	if len(tables) != 3 || tables[0] != "lines/线记录" || tables[1] != "points/点记录" || tables[2] != "polygons/面记录" {
		t.Fatalf("tables %v", tables)
	}
	var geom []byte
	var title, lith, by string
	var dip float64
	if err := g.QueryRow("SELECT geom, title, lithology, dip, updated_by FROM points WHERE record_id = 'a'").Scan(&geom, &title, &lith, &dip, &by); err != nil {
		t.Fatal(err)
	}
	if title != "D001" || lith != "灰岩" || dip != 40 || by != "甲" {
		t.Fatalf("point row %q %q %v %q", title, lith, dip, by)
	}
	// GeoPackage header, then WKB point (little endian, type 1) at 116.5, 40.
	if string(geom[:2]) != "GP" || geom[3] != 3 || binary.LittleEndian.Uint32(geom[4:]) != 4326 {
		t.Fatalf("header % x", geom[:8])
	}
	wkb := geom[40:]
	if wkb[0] != 1 || binary.LittleEndian.Uint32(wkb[1:]) != 1 || math.Float64frombits(binary.LittleEndian.Uint64(wkb[5:])) != 116.5 {
		t.Fatalf("wkb % x", wkb)
	}
	// The open ring was closed: 4 points.
	if err := g.QueryRow("SELECT geom FROM polygons").Scan(&geom); err != nil {
		t.Fatal(err)
	}
	if binary.LittleEndian.Uint32(geom[40+5:]) != 1 || binary.LittleEndian.Uint32(geom[40+9:]) != 4 {
		t.Fatalf("polygon % x", geom[40:60])
	}
	var style string
	var dipNull sql.NullFloat64
	g.QueryRow("SELECT line_style, dip FROM lines").Scan(&style, &dipNull)
	if style != "inferred_fault" || dipNull.Valid {
		t.Fatalf("line row %q %v", style, dipNull)
	}
	var color string
	g.QueryRow("SELECT color FROM polygons").Scan(&color)
	if color != "#22A884" {
		t.Fatalf("color %q", color)
	}
}

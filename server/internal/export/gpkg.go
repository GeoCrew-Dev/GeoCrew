package export

import (
	"context"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"math"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	_ "modernc.org/sqlite" // GeoPackage files are SQLite databases.
)

// gpkgLayer is one feature table: records are split by geometry family so
// each table has a single kind of geometry, as desktop GIS expects.
type gpkgLayer struct {
	table, title, geomType string
}

var gpkgLayers = map[string]gpkgLayer{
	"point":   {"points", "点记录", "GEOMETRY"},
	"line":    {"lines", "线记录", "GEOMETRY"},
	"polygon": {"polygons", "面记录", "GEOMETRY"},
}

// Columns copied out of the properties for convenience; the whole object
// stays in `properties` as JSON.
var gpkgColumns = []struct{ name, sqlType string }{
	{"kind", "TEXT"}, {"title", "TEXT"}, {"note", "TEXT"}, {"lithology", "TEXT"},
	{"strike", "REAL"}, {"dip_direction", "REAL"}, {"dip", "REAL"},
	{"line_style", "TEXT"}, {"meet_at", "TEXT"},
}

const wgs84WKT = `GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563,AUTHORITY["EPSG","7030"]],AUTHORITY["EPSG","6326"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4326"]]`

// GeoPackage writes the workspace's live records to a new GeoPackage 1.3
// file at path (which must not exist yet): one table each for points, lines
// and polygons in EPSG:4326 longitude/latitude (CGCS2000 is taken as equal
// at field accuracy), with the record id, the usual fields, the record's
// colour, who last changed it and when, and all its properties as JSON.
// QGIS, ArcGIS and most GIS open it directly. Geometry collections and
// records without a usable geometry are left out. Returns how many were written.
func GeoPackage(ctx context.Context, st store.Store, workspaceID, path string) (int, error) {
	ws, err := st.Workspace(ctx, workspaceID)
	if err != nil {
		return 0, err
	}
	lg := loadLegend(ctx, st, workspaceID)
	names := memberNames(ctx, st, workspaceID)

	db, err := sql.Open("sqlite", path)
	if err != nil {
		return 0, err
	}
	defer db.Close()
	db.SetMaxOpenConns(1)
	for _, q := range []string{
		"PRAGMA application_id = 1196444487", // 'GPKG'
		"PRAGMA user_version = 10300",
		"PRAGMA journal_mode = OFF",
		`CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER PRIMARY KEY, organization TEXT NOT NULL,
			organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT)`,
		`CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL, identifier TEXT UNIQUE,
			description TEXT DEFAULT '', last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
			min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, srs_id INTEGER,
			CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id))`,
		`CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL, geometry_type_name TEXT NOT NULL,
			srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL,
			CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name), CONSTRAINT uk_gc_table_name UNIQUE (table_name),
			CONSTRAINT fk_gc_tn FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name),
			CONSTRAINT fk_gc_srs FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys (srs_id))`,
	} {
		if _, err := db.ExecContext(ctx, q); err != nil {
			return 0, fmt.Errorf("gpkg schema: %w", err)
		}
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	for _, r := range [][]any{
		{"Undefined cartesian SRS", -1, "NONE", -1, "undefined", "undefined cartesian coordinate reference system"},
		{"Undefined geographic SRS", 0, "NONE", 0, "undefined", "undefined geographic coordinate reference system"},
		{"WGS 84 geodetic", 4326, "EPSG", 4326, wgs84WKT, "longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid"},
	} {
		if _, err := tx.ExecContext(ctx, `INSERT INTO gpkg_spatial_ref_sys VALUES (?, ?, ?, ?, ?, ?)`, r...); err != nil {
			return 0, err
		}
	}

	cols := make([]string, 0, len(gpkgColumns))
	marks := make([]string, 0, len(gpkgColumns))
	for _, c := range gpkgColumns {
		cols = append(cols, c.name+" "+c.sqlType)
		marks = append(marks, "?")
	}
	extents := map[string]*[4]float64{} // family → minX, minY, maxX, maxY
	n := 0
	err = eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		var g geometry
		if len(f.Geometry) == 0 || json.Unmarshal(f.Geometry, &g) != nil {
			return nil
		}
		w := &wkbWriter{env: [4]float64{math.Inf(1), math.Inf(1), math.Inf(-1), math.Inf(-1)}}
		family, ok := w.geometry(g)
		if !ok {
			return nil
		}
		layer := gpkgLayers[family]
		ext, made := extents[family]
		if !made {
			ext = &[4]float64{math.Inf(1), math.Inf(1), math.Inf(-1), math.Inf(-1)}
			extents[family] = ext
			if _, err := tx.ExecContext(ctx, `CREATE TABLE "`+layer.table+`" (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom BLOB, record_id TEXT NOT NULL, `+
				strings.Join(cols, ", ")+`, color TEXT, updated_at TEXT, updated_by TEXT, properties TEXT)`); err != nil {
				return err
			}
		}
		for i := 0; i < 2; i++ {
			ext[i] = math.Min(ext[i], w.env[i])
			ext[i+2] = math.Max(ext[i+2], w.env[i+2])
		}
		var p map[string]any
		json.Unmarshal(f.Properties, &p)
		args := []any{gpkgBlob(w), f.ID}
		for _, c := range gpkgColumns {
			switch v := p[c.name].(type) {
			case string:
				if c.sqlType == "TEXT" {
					args = append(args, v)
				} else {
					args = append(args, nil)
				}
			case float64:
				if c.sqlType == "REAL" {
					args = append(args, v)
				} else {
					args = append(args, nil)
				}
			default:
				args = append(args, nil)
			}
		}
		var color any
		if c := lg.colorOf(p); c != "" {
			color = c
		}
		by := f.UpdatedBy
		if name := names[by]; name != "" {
			by = name
		}
		args = append(args, color, f.UpdatedAt.UTC().Format(time.RFC3339), by, string(orNull(f.Properties)))
		if _, err := tx.ExecContext(ctx, `INSERT INTO "`+layer.table+`" (geom, record_id, `+gpkgColumnNames()+`, color, updated_at, updated_by, properties) VALUES (?, ?, `+
			strings.Join(marks, ", ")+`, ?, ?, ?, ?)`, args...); err != nil {
			return err
		}
		n++
		return nil
	})
	if err != nil {
		return n, err
	}
	for _, family := range []string{"point", "line", "polygon"} {
		ext, ok := extents[family]
		if !ok {
			continue
		}
		layer := gpkgLayers[family]
		if _, err := tx.ExecContext(ctx, `INSERT INTO gpkg_contents (table_name, data_type, identifier, description, min_x, min_y, max_x, max_y, srs_id)
			VALUES (?, 'features', ?, ?, ?, ?, ?, ?, 4326)`, layer.table, layer.title, ws.Name, ext[0], ext[1], ext[2], ext[3]); err != nil {
			return n, err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO gpkg_geometry_columns VALUES (?, 'geom', ?, 4326, 0, 0)`, layer.table, layer.geomType); err != nil {
			return n, err
		}
	}
	return n, tx.Commit()
}

func gpkgColumnNames() string {
	names := make([]string, len(gpkgColumns))
	for i, c := range gpkgColumns {
		names[i] = c.name
	}
	return strings.Join(names, ", ")
}

// memberNames maps member ids to nicknames, so the file says who, not which id.
func memberNames(ctx context.Context, st store.Store, workspaceID string) map[string]string {
	out := map[string]string{}
	ms, err := st.Members(ctx, workspaceID)
	if err != nil {
		return out
	}
	for _, m := range ms {
		out[m.ID] = m.Nickname
	}
	return out
}

// gpkgBlob is the GeoPackage binary header (little endian, XY envelope,
// SRS 4326) followed by the standard WKB.
func gpkgBlob(w *wkbWriter) []byte {
	b := make([]byte, 0, 40+len(w.buf))
	b = append(b, 'G', 'P', 0, 0x03)
	b = binary.LittleEndian.AppendUint32(b, 4326)
	for _, v := range []float64{w.env[0], w.env[2], w.env[1], w.env[3]} { // minx, maxx, miny, maxy
		b = binary.LittleEndian.AppendUint64(b, math.Float64bits(v))
	}
	return append(b, w.buf...)
}

// wkbWriter encodes GeoJSON geometry as little-endian 2D WKB and tracks its envelope.
type wkbWriter struct {
	buf []byte
	env [4]float64 // minX, minY, maxX, maxY
}

func (w *wkbWriter) head(t uint32, count int) {
	w.buf = append(w.buf, 1)
	w.buf = binary.LittleEndian.AppendUint32(w.buf, t)
	if count >= 0 {
		w.buf = binary.LittleEndian.AppendUint32(w.buf, uint32(count))
	}
}

func (w *wkbWriter) xy(c []float64) bool {
	if len(c) < 2 || math.IsNaN(c[0]) || math.IsNaN(c[1]) || math.IsInf(c[0], 0) || math.IsInf(c[1], 0) {
		return false
	}
	w.buf = binary.LittleEndian.AppendUint64(w.buf, math.Float64bits(c[0]))
	w.buf = binary.LittleEndian.AppendUint64(w.buf, math.Float64bits(c[1]))
	w.env[0] = math.Min(w.env[0], c[0])
	w.env[1] = math.Min(w.env[1], c[1])
	w.env[2] = math.Max(w.env[2], c[0])
	w.env[3] = math.Max(w.env[3], c[1])
	return true
}

func (w *wkbWriter) line(cs [][]float64, min int) bool {
	if len(cs) < min {
		return false
	}
	w.head(2, len(cs))
	for _, c := range cs {
		if !w.xy(c) {
			return false
		}
	}
	return true
}

// ring writes a polygon ring body (count and points, no header), closing it if needed.
func (w *wkbWriter) ring(cs [][]float64) bool {
	if len(cs) >= 3 && !equalXY(cs[0], cs[len(cs)-1]) {
		cs = append(cs, cs[0])
	}
	if len(cs) < 4 {
		return false
	}
	w.buf = binary.LittleEndian.AppendUint32(w.buf, uint32(len(cs)))
	for _, c := range cs {
		if !w.xy(c) {
			return false
		}
	}
	return true
}

func equalXY(a, b []float64) bool { return len(a) >= 2 && len(b) >= 2 && a[0] == b[0] && a[1] == b[1] }

func (w *wkbWriter) polygon(rings [][][]float64) bool {
	if len(rings) == 0 {
		return false
	}
	w.head(3, len(rings))
	for _, r := range rings {
		if !w.ring(r) {
			return false
		}
	}
	return true
}

// geometry writes g and returns its family (point, line or polygon).
func (w *wkbWriter) geometry(g geometry) (string, bool) {
	switch g.Type {
	case "Point":
		var c []float64
		if json.Unmarshal(g.Coordinates, &c) != nil {
			return "", false
		}
		w.head(1, -1)
		return "point", w.xy(c)
	case "MultiPoint":
		var cs [][]float64
		if json.Unmarshal(g.Coordinates, &cs) != nil || len(cs) == 0 {
			return "", false
		}
		w.head(4, len(cs))
		for _, c := range cs {
			w.head(1, -1)
			if !w.xy(c) {
				return "", false
			}
		}
		return "point", true
	case "LineString":
		var cs [][]float64
		if json.Unmarshal(g.Coordinates, &cs) != nil {
			return "", false
		}
		return "line", w.line(cs, 2)
	case "MultiLineString":
		var ls [][][]float64
		if json.Unmarshal(g.Coordinates, &ls) != nil || len(ls) == 0 {
			return "", false
		}
		w.head(5, len(ls))
		for _, l := range ls {
			if !w.line(l, 2) {
				return "", false
			}
		}
		return "line", true
	case "Polygon":
		var rings [][][]float64
		if json.Unmarshal(g.Coordinates, &rings) != nil {
			return "", false
		}
		return "polygon", w.polygon(rings)
	case "MultiPolygon":
		var ps [][][][]float64
		if json.Unmarshal(g.Coordinates, &ps) != nil || len(ps) == 0 {
			return "", false
		}
		w.head(6, len(ps))
		for _, p := range ps {
			if !w.polygon(p) {
				return "", false
			}
		}
		return "polygon", true
	}
	return "", false
}

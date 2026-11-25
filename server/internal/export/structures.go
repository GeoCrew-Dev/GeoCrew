package export

import (
	"bufio"
	"context"
	"encoding/csv"
	"encoding/json"
	"io"
	"math"
	"sort"
	"strconv"
	"strings"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// StructureHeader is the column order of the structural measurement table,
// the same as the Android client's "产状表" export.
var StructureHeader = []string{"记录", "种类", "类型", "倾向或倾伏向(°)", "倾角或倾伏角(°)", "走向(°)", "经度", "纬度", "高程(m)", "岩性", "记录ID"}

// lineStyleLabels names the line styles of protocol v0 §6.7.
var lineStyleLabels = map[string]string{
	"contact":          "实测地质界线",
	"inferred_contact": "推测地质界线",
	"fault":            "实测断层",
	"inferred_fault":   "推测断层",
}

// Structures writes every planar attitude (strike, dip_direction, dip) and
// lineation (trend, plunge, lineation_type; protocol v0 §6.12) in the
// workspace as one row each, records in natural title order, and returns the
// row count. A record with both gives two rows; lines and polygons are
// placed at their first vertex.
func Structures(ctx context.Context, st store.Store, workspaceID string, out io.Writer) (int, error) {
	if _, err := st.Workspace(ctx, workspaceID); err != nil {
		return 0, err
	}
	type record struct {
		title string
		rows  [][]string
	}
	var recs []record
	err := eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		var p map[string]any
		if json.Unmarshal(f.Properties, &p) != nil {
			return nil
		}
		title := strings.TrimSpace(str(p["title"]))
		lng, lat, alt := "", "", ""
		if lo, la, al := firstPosition(f.Geometry); lo != "" {
			x, e1 := strconv.ParseFloat(lo, 64)
			y, e2 := strconv.ParseFloat(la, 64)
			if e1 == nil && e2 == nil {
				lng, lat = fixed(x, 7), fixed(y, 7)
				if z, err := strconv.ParseFloat(al, 64); err == nil {
					alt = fixed(z, 1)
				}
			}
		}
		place := []string{lng, lat, alt, str(p["lithology"]), f.ID}
		var geomType struct {
			Type string `json:"type"`
		}
		_ = json.Unmarshal(f.Geometry, &geomType)
		var rows [][]string
		if dd, ok1 := num(p["dip_direction"]); ok1 {
			if dip, ok2 := num(p["dip"]); ok2 && dd >= 0 && dd <= 360 && dip >= 0 && dip <= 90 {
				dd = math.Mod(dd, 360)
				kind := ""
				if geomType.Type != "Point" {
					kind = lineStyleLabels[str(p["line_style"])]
				}
				rows = append(rows, append([]string{title, "面状", kind, fixed(dd, 1), fixed(dip, 1), fixed(math.Mod(dd+270, 360), 1)}, place...))
			}
		}
		if tr, ok1 := num(p["trend"]); ok1 {
			if pl, ok2 := num(p["plunge"]); ok2 && tr >= 0 && tr <= 360 && pl >= 0 && pl <= 90 {
				kind := strings.TrimSpace(str(p["lineation_type"]))
				if kind == "" {
					kind = "线理"
				}
				rows = append(rows, append([]string{title, "线状", kind, fixed(math.Mod(tr, 360), 1), fixed(pl, 1), ""}, place...))
			}
		}
		if len(rows) > 0 {
			recs = append(recs, record{title, rows})
		}
		return nil
	})
	if err != nil {
		return 0, err
	}
	sort.SliceStable(recs, func(i, j int) bool { return naturalLess(recs[i].title, recs[j].title) })

	bw := bufio.NewWriter(out)
	bw.WriteString("\uFEFF")
	w := csv.NewWriter(bw)
	w.UseCRLF = true
	if err := w.Write(StructureHeader); err != nil {
		return 0, err
	}
	n := 0
	for _, rec := range recs {
		for _, r := range rec.rows {
			for i, c := range r {
				r[i] = defuse(c)
			}
			if err := w.Write(r); err != nil {
				return n, err
			}
			n++
		}
	}
	w.Flush()
	if err := w.Error(); err != nil {
		return n, err
	}
	return n, bw.Flush()
}

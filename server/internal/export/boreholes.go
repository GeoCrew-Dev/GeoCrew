package export

import (
	"bufio"
	"context"
	"encoding/csv"
	"encoding/json"
	"io"
	"sort"
	"strings"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// BoreholeHeader is the column order of the borehole layer table, the same
// as the Android client's "钻孔分层表" export.
var BoreholeHeader = []string{"孔号", "孔口经度", "孔口纬度", "孔口高程(m)", "终孔深度(m)", "水位埋深(m)", "层号", "层顶深度(m)", "层底深度(m)", "厚度(m)", "岩性", "描述", "记录ID"}

// maxBoreDepth matches the protocol's 0–5000 m range for depths.
const maxBoreDepth = 5000

// Boreholes writes every borehole log (protocol v0 §6.11) in the workspace
// as a layer table, one row per layer, holes in natural title order, and
// returns the row count. A layer's top is the bottom of the layer above;
// layers that do not go deeper are skipped, as the protocol says.
func Boreholes(ctx context.Context, st store.Store, workspaceID string, out io.Writer) (int, error) {
	if _, err := st.Workspace(ctx, workspaceID); err != nil {
		return 0, err
	}
	type hole struct {
		title string
		rows  [][]string
	}
	var holes []hole
	err := eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		var p struct {
			Kind     any `json:"kind"`
			Title    any `json:"title"`
			Borehole struct {
				Depth  any              `json:"depth"`
				Water  any              `json:"water"`
				Layers []map[string]any `json:"layers"`
			} `json:"borehole"`
		}
		if json.Unmarshal(f.Properties, &p) != nil || str(p.Kind) != "borehole" {
			return nil
		}
		title := strings.TrimSpace(str(p.Title))
		if title == "" {
			title = "钻孔"
		}
		lng, lat, alt := "", "", ""
		var g struct {
			Type        string    `json:"type"`
			Coordinates []float64 `json:"coordinates"`
		}
		if len(f.Geometry) > 0 && json.Unmarshal(f.Geometry, &g) == nil && g.Type == "Point" && len(g.Coordinates) >= 2 {
			lng, lat = fixed(g.Coordinates[0], 7), fixed(g.Coordinates[1], 7)
			if len(g.Coordinates) > 2 {
				alt = fixed(g.Coordinates[2], 1)
			}
		}
		depthOK := func(v any) (float64, bool) {
			d, ok := num(v)
			return d, ok && d >= 0 && d <= maxBoreDepth
		}
		type layer struct {
			no, lithology, note string
			top, bottom         float64
		}
		var layers []layer
		top := 0.0
		for _, l := range p.Borehole.Layers {
			b, ok := depthOK(l["bottom"])
			if !ok || !(b > top) {
				continue
			}
			layers = append(layers, layer{str(l["no"]), str(l["lithology"]), str(l["note"]), top, b})
			top = b
		}
		total := top
		if d, ok := depthOK(p.Borehole.Depth); ok && d > 0 {
			total = d
		}
		water := ""
		if w, ok := depthOK(p.Borehole.Water); ok {
			water = fixed(w, 2)
		}
		h := hole{title: title}
		for _, l := range layers {
			h.rows = append(h.rows, []string{title, lng, lat, alt, fixed(total, 2), water, l.no, fixed(l.top, 2), fixed(l.bottom, 2), fixed(l.bottom-l.top, 2), l.lithology, l.note, f.ID})
		}
		holes = append(holes, h)
		return nil
	})
	if err != nil {
		return 0, err
	}
	sort.SliceStable(holes, func(i, j int) bool { return naturalLess(holes[i].title, holes[j].title) })

	bw := bufio.NewWriter(out)
	bw.WriteString("\uFEFF")
	w := csv.NewWriter(bw)
	w.UseCRLF = true
	if err := w.Write(BoreholeHeader); err != nil {
		return 0, err
	}
	n := 0
	for _, h := range holes {
		for _, r := range h.rows {
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

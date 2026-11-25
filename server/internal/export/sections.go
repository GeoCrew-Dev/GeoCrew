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

// SectionHeader is the column order of the measured-section layer table,
// the same as the Android client's "实测剖面分层表" export.
var SectionHeader = []string{"剖面", "层号", "起点斜距(m)", "终点斜距(m)", "岩性", "倾向", "倾角", "产状来源", "真厚度(m)", "备注", "记录ID"}

type sectionLeg struct {
	d float64
	v [3]float64 // east, north, up, m
}

type sectionLayer struct {
	no, lithology, note string
	top                 float64
	dd, dip             float64
	measured            bool
}

// Sections writes every measured section (protocol v0 §6.5) in the
// workspace as a layer table, one row per layer, sections in natural title
// order, and returns the row count. Thicknesses are computed the way the
// protocol describes; a layer without its own attitude borrows the nearest
// measured one and is marked "借用".
func Sections(ctx context.Context, st store.Store, workspaceID string, out io.Writer) (int, error) {
	if _, err := st.Workspace(ctx, workspaceID); err != nil {
		return 0, err
	}
	type section struct {
		title, id string
		rows      [][]string
	}
	var secs []section
	err := eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		var p struct {
			Kind    any `json:"kind"`
			Title   any `json:"title"`
			Section struct {
				Legs   []map[string]any `json:"legs"`
				Layers []map[string]any `json:"layers"`
			} `json:"section"`
		}
		if json.Unmarshal(f.Properties, &p) != nil || str(p.Kind) != "section" {
			return nil
		}
		title := strings.TrimSpace(str(p.Title))
		if title == "" {
			title = "实测剖面"
		}
		secs = append(secs, section{title, f.ID, sectionRows(p.Section.Legs, p.Section.Layers, f.ID, title)})
		return nil
	})
	if err != nil {
		return 0, err
	}
	sort.SliceStable(secs, func(i, j int) bool { return naturalLess(secs[i].title, secs[j].title) })

	bw := bufio.NewWriter(out)
	bw.WriteString("\uFEFF")
	w := csv.NewWriter(bw)
	w.UseCRLF = true
	if err := w.Write(SectionHeader); err != nil {
		return 0, err
	}
	n := 0
	for _, s := range secs {
		for _, r := range s.rows {
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

func sectionRows(rawLegs, rawLayers []map[string]any, id, title string) [][]string {
	const rad = math.Pi / 180
	var legs []sectionLeg
	total := 0.0
	for _, l := range rawLegs {
		az, ok1 := num(l["azimuth"])
		d, ok2 := num(l["distance"])
		if !ok1 || !ok2 || d <= 0 {
			continue
		}
		slope, _ := num(l["slope"])
		slope = math.Max(-90, math.Min(90, slope))
		b, a := slope*rad, az*rad
		h := d * math.Cos(b)
		legs = append(legs, sectionLeg{d, [3]float64{h * math.Sin(a), h * math.Cos(a), d * math.Sin(b)}})
		total += d
	}
	var layers []sectionLayer
	for _, l := range rawLayers {
		top, ok := num(l["top"])
		if !ok {
			continue
		}
		x := sectionLayer{no: str(l["no"]), lithology: str(l["lithology"]), note: str(l["note"]), top: top}
		dd, ok1 := num(l["dip_direction"])
		dip, ok2 := num(l["dip"])
		if ok1 && ok2 && dip >= 0 && dip <= 90 {
			x.dd, x.dip, x.measured = math.Mod(math.Mod(dd, 360)+360, 360), dip, true
		}
		layers = append(layers, x)
	}
	rows := make([][]string, 0, len(layers))
	from := 0.0
	for i, l := range layers {
		a, b := from, math.Min(l.top, total)
		from = math.Max(from, l.top)
		att, source := -1, ""
		if l.measured {
			att, source = i, "实测"
		} else {
			best := math.Inf(1)
			for j, k := range layers {
				if !k.measured {
					continue
				}
				c := math.Abs(float64(j-i)) * 2
				if j >= i {
					c++
				}
				if c < best {
					best, att = c, j
				}
			}
			if att >= 0 {
				source = "借用"
			}
		}
		dd, dip, thick := "", "", ""
		if att >= 0 {
			t := layers[att]
			dd, dip = fixed(t.dd, 0), fixed(t.dip, 0)
			sum := 0.0
			if b > a {
				n := [3]float64{math.Sin(t.dip*rad) * math.Sin(t.dd*rad), math.Sin(t.dip*rad) * math.Cos(t.dd*rad), math.Cos(t.dip * rad)}
				start := 0.0
				for _, leg := range legs {
					end := start + leg.d
					if piece := math.Min(end, b) - math.Max(start, a); piece > 0 {
						sum += (leg.v[0]*n[0] + leg.v[1]*n[1] + leg.v[2]*n[2]) * piece / leg.d
					}
					start = end
				}
			}
			thick = fixed(math.Abs(sum), 2)
		}
		rows = append(rows, []string{title, l.no, fixed(a, 2), fixed(l.top, 2), l.lithology, dd, dip, source, thick, l.note, id})
	}
	return rows
}

func num(v any) (float64, bool) {
	f, ok := v.(float64)
	return f, ok && !math.IsNaN(f) && !math.IsInf(f, 0)
}

func fixed(v float64, digits int) string {
	s := strconv.FormatFloat(v, 'f', digits, 64)
	if s == "-0" || strings.Trim(s, "-0.") == "" && strings.HasPrefix(s, "-") {
		s = s[1:]
	}
	return s
}

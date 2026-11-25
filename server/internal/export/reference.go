package export

import (
	"encoding/json"
	"fmt"
	"io"
	"math"
	"sort"
	"strconv"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/blob"
)

// ReferenceLayer is one shared reference layer of the settings (protocol
// v0 §2.2, key `reference_layers`): earlier mapping, licence areas and the
// like, kept as a GeoJSON attachment for looking up, not as records.
type ReferenceLayer struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	File    string `json:"file"`
	ColorBy string `json:"color_by"`
}

// ReferenceLayers lists the valid reference layers the settings share.
func ReferenceLayers(settings json.RawMessage) []ReferenceLayer {
	var s struct {
		References []ReferenceLayer `json:"reference_layers"`
	}
	if len(settings) == 0 || json.Unmarshal(settings, &s) != nil {
		return nil
	}
	var out []ReferenceLayer
	for _, l := range s.References {
		if blob.ValidHash(l.File) && l.Name != "" {
			out = append(out, l)
		}
	}
	return out
}

// ReferenceHit is a shape of a reference layer at or near a position.
type ReferenceHit struct {
	Layer string `json:"layer"`
	// Value is the layer's colouring attribute (e.g. the map unit), if any.
	Value string `json:"value,omitempty"`
	// Attributes are the shape's properties, at most a dozen.
	Attributes map[string]any `json:"attributes,omitempty"`
	// DistanceM is set for lines: how far the position is from it.
	DistanceM *float64 `json:"distance_m,omitempty"`
}

// MaxReferenceBytes bounds one reference layer read for a lookup.
const MaxReferenceBytes = 32 << 20

// ReferenceAt reads one reference layer's GeoJSON and returns the areas
// that contain (lon, lat), with holes, and the lines within reachM of it,
// nearest first.
func ReferenceAt(layer ReferenceLayer, r io.Reader, lon, lat, reachM float64) (areas, lines []ReferenceHit, err error) {
	var fc struct {
		Features []struct {
			Geometry struct {
				Type        string          `json:"type"`
				Coordinates json.RawMessage `json:"coordinates"`
			} `json:"geometry"`
			Properties map[string]any `json:"properties"`
		} `json:"features"`
	}
	if err := json.NewDecoder(io.LimitReader(r, MaxReferenceBytes)).Decode(&fc); err != nil {
		return nil, nil, fmt.Errorf("reference layer %s: %w", layer.ID, err)
	}
	// Local flat projection around the position, metres.
	kx := 111320 * math.Cos(lat*math.Pi/180)
	const ky = 110540.0
	xy := func(p []float64) (float64, float64) { return (p[0] - lon) * kx, (p[1] - lat) * ky }
	hit := func(props map[string]any) ReferenceHit {
		h := ReferenceHit{Layer: layer.Name, Value: text(props[layer.ColorBy])}
		keys := make([]string, 0, len(props))
		for k := range props {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			if len(h.Attributes) == 12 {
				break
			}
			if v := props[k]; v != nil {
				if h.Attributes == nil {
					h.Attributes = map[string]any{}
				}
				h.Attributes[k] = v
			}
		}
		return h
	}
	inRings := func(rings [][][]float64) bool {
		in := false
		for _, ring := range rings {
			for i, j := 0, len(ring)-1; i < len(ring); j, i = i, i+1 {
				if len(ring[i]) < 2 || len(ring[j]) < 2 {
					continue
				}
				xi, yi := xy(ring[i])
				xj, yj := xy(ring[j])
				if (yi > 0) != (yj > 0) && 0 < (xj-xi)*(0-yi)/(yj-yi)+xi {
					in = !in
				}
			}
		}
		return in
	}
	lineDist := func(l [][]float64) float64 {
		best := math.Inf(1)
		for i := 1; i < len(l); i++ {
			if len(l[i-1]) < 2 || len(l[i]) < 2 {
				continue
			}
			ax, ay := xy(l[i-1])
			bx, by := xy(l[i])
			dx, dy := bx-ax, by-ay
			t := 0.0
			if d := dx*dx + dy*dy; d > 0 {
				t = math.Max(0, math.Min(1, -(ax*dx+ay*dy)/d))
			}
			best = math.Min(best, math.Hypot(ax+t*dx, ay+t*dy))
		}
		return best
	}
	for _, f := range fc.Features {
		g := f.Geometry
		switch g.Type {
		case "Polygon", "MultiPolygon":
			var polys [][][][]float64
			if g.Type == "Polygon" {
				var p [][][]float64
				if json.Unmarshal(g.Coordinates, &p) != nil {
					continue
				}
				polys = [][][][]float64{p}
			} else if json.Unmarshal(g.Coordinates, &polys) != nil {
				continue
			}
			for _, p := range polys {
				if inRings(p) {
					areas = append(areas, hit(f.Properties))
					break
				}
			}
		case "LineString", "MultiLineString":
			var ls [][][]float64
			if g.Type == "LineString" {
				var l [][]float64
				if json.Unmarshal(g.Coordinates, &l) != nil {
					continue
				}
				ls = [][][]float64{l}
			} else if json.Unmarshal(g.Coordinates, &ls) != nil {
				continue
			}
			d := math.Inf(1)
			for _, l := range ls {
				d = math.Min(d, lineDist(l))
			}
			if d <= reachM {
				h := hit(f.Properties)
				m := math.Round(d)
				h.DistanceM = &m
				lines = append(lines, h)
			}
		}
	}
	sort.SliceStable(lines, func(i, j int) bool { return *lines[i].DistanceM < *lines[j].DistanceM })
	return areas, lines, nil
}

// text renders an attribute value for display.
func text(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(x)
	}
	b, _ := json.Marshal(v)
	return string(b)
}

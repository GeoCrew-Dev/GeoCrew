package export

import (
	"bufio"
	"context"
	"encoding/json"
	"encoding/xml"
	"io"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

var hexColor = regexp.MustCompile(`^#[0-9a-fA-F]{6}$`)

// legend is the shared map legend (protocol v0 §2.2, key `legend`).
type legend struct {
	key    string
	colors map[string]string // lower-cased value → #RRGGBB
}

func loadLegend(ctx context.Context, st store.Store, workspaceID string) legend {
	raw, _, err := st.Settings(ctx, workspaceID)
	if err != nil {
		return legend{}
	}
	var s struct {
		Legend struct {
			Key     string `json:"key"`
			Entries []struct {
				Value string `json:"value"`
				Color string `json:"color"`
			} `json:"entries"`
		} `json:"legend"`
	}
	if json.Unmarshal(raw, &s) != nil {
		return legend{}
	}
	l := legend{key: strings.TrimSpace(s.Legend.Key), colors: map[string]string{}}
	for _, e := range s.Legend.Entries {
		v := strings.ToLower(strings.TrimSpace(e.Value))
		if _, dup := l.colors[v]; v == "" || dup || !hexColor.MatchString(e.Color) {
			continue
		}
		l.colors[v] = strings.ToUpper(e.Color)
	}
	return l
}

// colorOf is a record's own simplestyle colour, else its legend colour, else "".
func (l legend) colorOf(p map[string]any) string {
	for _, k := range []string{"stroke", "fill", "marker-color"} {
		if c, ok := p[k].(string); ok && hexColor.MatchString(c) {
			return strings.ToUpper(c)
		}
	}
	if l.key == "" {
		return ""
	}
	var v string
	switch x := p[l.key].(type) {
	case string:
		v = x
	case float64:
		v = strconv.FormatFloat(x, 'f', -1, 64)
	default:
		return ""
	}
	return l.colors[strings.ToLower(strings.TrimSpace(v))]
}

// KML streams the workspace's live records as a KML 2.2 document for
// Google Earth, 奥维互动地图 and desktop GIS: title and note as name and
// description, other simple properties as ExtendedData, and colours from a
// record's own simplestyle keys or the shared legend as shared styles.
// Records without geometry are left out. Returns how many were written.
func KML(ctx context.Context, st store.Store, workspaceID string, out io.Writer) (int, error) {
	ws, err := st.Workspace(ctx, workspaceID)
	if err != nil {
		return 0, err
	}
	lg := loadLegend(ctx, st, workspaceID)

	// First pass: the colours in use, so their styles open the document.
	used := map[string]bool{}
	err = eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		var p map[string]any
		if len(f.Geometry) > 0 && string(f.Geometry) != "null" && json.Unmarshal(f.Properties, &p) == nil {
			if c := lg.colorOf(p); c != "" {
				used[c] = true
			}
		}
		return nil
	})
	if err != nil {
		return 0, err
	}
	colors := make([]string, 0, len(used))
	for c := range used {
		colors = append(colors, c)
	}
	sort.Strings(colors)

	w := bufio.NewWriter(out)
	w.WriteString(xml.Header)
	w.WriteString(`<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>`)
	xmlText(w, ws.Name)
	w.WriteString("</name>\n")
	for _, c := range colors {
		w.WriteString(`<Style id="c` + c[1:] + `"><IconStyle><color>` + kmlColor(c, "ff") + `</color></IconStyle>` +
			`<LineStyle><color>` + kmlColor(c, "ff") + `</color><width>3</width></LineStyle>` +
			`<PolyStyle><color>` + kmlColor(c, "73") + "</color></PolyStyle></Style>\n")
	}

	n := 0
	err = eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		var g geometry
		if len(f.Geometry) == 0 || json.Unmarshal(f.Geometry, &g) != nil {
			return nil
		}
		var body strings.Builder
		if !writeGeometry(&body, g) {
			return nil
		}
		var p map[string]any
		json.Unmarshal(f.Properties, &p)
		w.WriteString("<Placemark>")
		if t := strings.TrimSpace(str(p["title"])); t != "" {
			w.WriteString("<name>")
			xmlText(w, t)
			w.WriteString("</name>")
		}
		if t := strings.TrimSpace(str(p["note"])); t != "" {
			w.WriteString("<description>")
			xmlText(w, t)
			w.WriteString("</description>")
		}
		if str(p["kind"]) == "track" {
			timeSpan(w, p)
		}
		if c := lg.colorOf(p); c != "" {
			w.WriteString("<styleUrl>#c" + c[1:] + "</styleUrl>")
		}
		extendedData(w, f.ID, p)
		w.WriteString(body.String())
		w.WriteString("</Placemark>\n")
		n++
		return nil
	})
	if err != nil {
		return n, err
	}
	w.WriteString("</Document></kml>\n")
	return n, w.Flush()
}

// KML colours are aabbggrr.
func kmlColor(hex, alpha string) string {
	return strings.ToLower(alpha + hex[5:7] + hex[3:5] + hex[1:3])
}

func xmlText(w *bufio.Writer, s string) { xml.EscapeText(w, []byte(s)) }

// timeSpan writes a track's started_at and ended_at as a KML TimeSpan, so
// Google Earth's time slider can show it; nothing when the start is missing.
func timeSpan(w *bufio.Writer, p map[string]any) {
	begin, err := time.Parse(time.RFC3339, str(p["started_at"]))
	if err != nil {
		return
	}
	w.WriteString("<TimeSpan><begin>" + begin.UTC().Format(time.RFC3339) + "</begin>")
	if end, err := time.Parse(time.RFC3339, str(p["ended_at"])); err == nil && !end.Before(begin) {
		w.WriteString("<end>" + end.UTC().Format(time.RFC3339) + "</end>")
	}
	w.WriteString("</TimeSpan>")
}

func extendedData(w *bufio.Writer, id string, p map[string]any) {
	keys := make([]string, 0, len(p))
	for k := range p {
		// Per-fix times (protocol v0 §6.13) go out as the TimeSpan instead.
		if k != "title" && k != "note" && k != "times_s" {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	w.WriteString("<ExtendedData>")
	data := func(k, v string) {
		w.WriteString(`<Data name="`)
		xmlText(w, k)
		w.WriteString(`"><value>`)
		xmlText(w, v)
		w.WriteString("</value></Data>")
	}
	data("id", id)
	for _, k := range keys {
		var v string
		switch x := p[k].(type) {
		case string:
			v = x
		case float64:
			v = strconv.FormatFloat(x, 'f', -1, 64)
		case bool:
			v = strconv.FormatBool(x)
		case []any:
			// Lists of plain values (photo hashes, tags) read as a comma list;
			// sample objects as their numbers.
			var parts []string
			for _, e := range x {
				switch y := e.(type) {
				case string:
					parts = append(parts, y)
				case float64:
					parts = append(parts, strconv.FormatFloat(y, 'f', -1, 64))
				case map[string]any:
					if no := str(y["no"]); no != "" {
						parts = append(parts, no)
					}
				}
			}
			if len(parts) == 0 {
				continue
			}
			v = strings.Join(parts, ",")
		default:
			continue
		}
		data(k, v)
	}
	w.WriteString("</ExtendedData>")
}

type geometry struct {
	Type        string          `json:"type"`
	Coordinates json.RawMessage `json:"coordinates"`
	Geometries  []geometry      `json:"geometries"`
}

// writeGeometry appends g as KML and reports whether it was usable.
func writeGeometry(b *strings.Builder, g geometry) bool {
	switch g.Type {
	case "Point":
		var c []float64
		if json.Unmarshal(g.Coordinates, &c) != nil || len(c) < 2 {
			return false
		}
		b.WriteString("<Point><coordinates>" + coord(c) + "</coordinates></Point>")
	case "LineString":
		var cs [][]float64
		if json.Unmarshal(g.Coordinates, &cs) != nil || !line(b, cs) {
			return false
		}
	case "Polygon":
		var rings [][][]float64
		if json.Unmarshal(g.Coordinates, &rings) != nil || !polygon(b, rings) {
			return false
		}
	case "MultiPoint", "MultiLineString", "MultiPolygon":
		var parts []json.RawMessage
		if json.Unmarshal(g.Coordinates, &parts) != nil || len(parts) == 0 {
			return false
		}
		single := strings.TrimPrefix(g.Type, "Multi")
		b.WriteString("<MultiGeometry>")
		for _, c := range parts {
			if !writeGeometry(b, geometry{Type: single, Coordinates: c}) {
				return false
			}
		}
		b.WriteString("</MultiGeometry>")
	case "GeometryCollection":
		if len(g.Geometries) == 0 {
			return false
		}
		b.WriteString("<MultiGeometry>")
		for _, c := range g.Geometries {
			if !writeGeometry(b, c) {
				return false
			}
		}
		b.WriteString("</MultiGeometry>")
	default:
		return false
	}
	return true
}

func line(b *strings.Builder, cs [][]float64) bool {
	if len(cs) < 2 {
		return false
	}
	b.WriteString("<LineString><coordinates>")
	if !coords(b, cs) {
		return false
	}
	b.WriteString("</coordinates></LineString>")
	return true
}

func polygon(b *strings.Builder, rings [][][]float64) bool {
	if len(rings) == 0 {
		return false
	}
	b.WriteString("<Polygon>")
	for i, r := range rings {
		tag := "innerBoundaryIs"
		if i == 0 {
			tag = "outerBoundaryIs"
		}
		b.WriteString("<" + tag + "><LinearRing><coordinates>")
		if len(r) < 4 || !coords(b, r) {
			return false
		}
		b.WriteString("</coordinates></LinearRing></" + tag + ">")
	}
	b.WriteString("</Polygon>")
	return true
}

func coords(b *strings.Builder, cs [][]float64) bool {
	for i, c := range cs {
		if len(c) < 2 {
			return false
		}
		if i > 0 {
			b.WriteByte(' ')
		}
		b.WriteString(coord(c))
	}
	return true
}

func coord(c []float64) string {
	s := strconv.FormatFloat(c[0], 'f', -1, 64) + "," + strconv.FormatFloat(c[1], 'f', -1, 64)
	if len(c) > 2 {
		s += "," + strconv.FormatFloat(c[2], 'f', -1, 64)
	}
	return s
}

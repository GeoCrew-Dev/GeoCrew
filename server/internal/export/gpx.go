package export

import (
	"bufio"
	"context"
	"encoding/json"
	"encoding/xml"
	"io"
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
)

// GPX writes the workspace as GPX 1.1 for GPS units, hiking apps and
// desktop GIS: points become waypoints, tracks become tracks with a time on
// each fix when the record carries times_s (protocol v0 §6.13), and other
// lines (geological lines, routes, sections) become routes. Areas have no
// GPX form and are left out. Returns how many records were written.
func GPX(ctx context.Context, st store.Store, workspaceID string, out io.Writer) (int, error) {
	ws, err := st.Workspace(ctx, workspaceID)
	if err != nil {
		return 0, err
	}
	// GPX fixes the order of waypoints, routes and tracks, so each goes to
	// its own buffer first.
	var wpts, rtes, trks strings.Builder
	n := 0
	err = eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		var g geometry
		if len(f.Geometry) == 0 || json.Unmarshal(f.Geometry, &g) != nil {
			return nil
		}
		var p map[string]any
		json.Unmarshal(f.Properties, &p)
		meta := gpxMeta(p)
		switch g.Type {
		case "Point":
			var c []float64
			if json.Unmarshal(g.Coordinates, &c) != nil || len(c) < 2 {
				return nil
			}
			gpxPoint(&wpts, "wpt", c, "", meta)
			wpts.WriteByte('\n')
		case "LineString", "MultiLineString":
			var lines [][][]float64
			if g.Type == "LineString" {
				var cs [][]float64
				if json.Unmarshal(g.Coordinates, &cs) != nil {
					return nil
				}
				lines = [][][]float64{cs}
			} else if json.Unmarshal(g.Coordinates, &lines) != nil {
				return nil
			}
			if !usableLines(lines) {
				return nil
			}
			if str(p["kind"]) == "track" {
				var times []string
				if g.Type == "LineString" {
					times = trackTimes(p, len(lines[0]))
				}
				trks.WriteString("<trk>" + meta)
				for _, cs := range lines {
					trks.WriteString("<trkseg>")
					for i, c := range cs {
						t := ""
						if times != nil {
							t = times[i]
						}
						gpxPoint(&trks, "trkpt", c, t, "")
					}
					trks.WriteString("</trkseg>")
				}
				trks.WriteString("</trk>\n")
			} else {
				// A route has no segments: each part of a multi-line is its own.
				for _, cs := range lines {
					rtes.WriteString("<rte>" + meta)
					for _, c := range cs {
						gpxPoint(&rtes, "rtept", c, "", "")
					}
					rtes.WriteString("</rte>\n")
				}
			}
		default:
			return nil
		}
		n++
		return nil
	})
	if err != nil {
		return n, err
	}
	w := bufio.NewWriter(out)
	w.WriteString(xml.Header)
	w.WriteString(`<gpx version="1.1" creator="GeoCrew Server" xmlns="http://www.topografix.com/GPX/1/1"><metadata><name>`)
	xmlText(w, ws.Name)
	w.WriteString("</name></metadata>\n")
	w.WriteString(wpts.String())
	w.WriteString(rtes.String())
	w.WriteString(trks.String())
	w.WriteString("</gpx>\n")
	return n, w.Flush()
}

func usableLines(lines [][][]float64) bool {
	if len(lines) == 0 {
		return false
	}
	for _, cs := range lines {
		if len(cs) < 2 {
			return false
		}
		for _, c := range cs {
			if len(c) < 2 {
				return false
			}
		}
	}
	return true
}

// gpxMeta is a record's name and description as GPX elements.
func gpxMeta(p map[string]any) string {
	var b strings.Builder
	esc := func(s string) string {
		var e strings.Builder
		xml.EscapeText(&e, []byte(s))
		return e.String()
	}
	if t := strings.TrimSpace(str(p["title"])); t != "" {
		b.WriteString("<name>" + esc(t) + "</name>")
	}
	if t := strings.TrimSpace(str(p["note"])); t != "" {
		b.WriteString("<desc>" + esc(t) + "</desc>")
	}
	return b.String()
}

func gpxPoint(b *strings.Builder, tag string, c []float64, at, inner string) {
	b.WriteString("<" + tag + ` lat="` + strconv.FormatFloat(c[1], 'f', -1, 64) + `" lon="` + strconv.FormatFloat(c[0], 'f', -1, 64) + `">`)
	// GPX wants ele, then time, then name and desc.
	if len(c) > 2 {
		b.WriteString("<ele>" + strconv.FormatFloat(c[2], 'f', -1, 64) + "</ele>")
	}
	if at != "" {
		b.WriteString("<time>" + at + "</time>")
	}
	b.WriteString(inner + "</" + tag + ">")
}

// trackTimes reads times_s (protocol v0 §6.13) as one RFC 3339 time per
// coordinate, or nil when it is missing or unusable.
func trackTimes(p map[string]any, count int) []string {
	start, err := time.Parse(time.RFC3339, str(p["started_at"]))
	if err != nil {
		return nil
	}
	raw, ok := p["times_s"].([]any)
	if !ok || len(raw) != count {
		return nil
	}
	out := make([]string, len(raw))
	last := math.Inf(-1)
	for i, v := range raw {
		s, ok := v.(float64)
		if !ok || s != math.Trunc(s) || s < last || math.Abs(s) > 1e10 {
			return nil
		}
		last = s
		out[i] = start.Add(time.Duration(s) * time.Second).UTC().Format(time.RFC3339)
	}
	return out
}

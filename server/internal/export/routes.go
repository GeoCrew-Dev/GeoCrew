package export

import (
	"bufio"
	"context"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"sort"
	"strconv"
	"strings"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// RouteHeader is the column order of the geological route table, the same
// as the Android client's "地质路线记录表" export.
var RouteHeader = []string{"路线", "日期", "参加人员", "序号", "点号", "类型", "经度", "纬度", "海拔(m)", "岩性", "产状", "点上描述", "至下一点描述", "记录ID"}

var kindNames = map[string]string{"geo_point": "地质点", "photo": "照片点", "point": "标注点"}

// Routes writes every geological route (protocol v0 §6.6) in the workspace
// as a route record table: per route a purpose row, one row per stop in
// walking order with the stop record's position, lithology, attitude and
// note plus the description on to the next stop, and a summary row. Routes
// are ordered by date, then natural route number. Stops whose record is
// missing or deleted are skipped. Returns the row count.
func Routes(ctx context.Context, st store.Store, workspaceID string, out io.Writer) (int, error) {
	if _, err := st.Workspace(ctx, workspaceID); err != nil {
		return 0, err
	}
	type stop struct{ Feature, Onward any }
	type route struct {
		id, title, date, members, purpose, summary string
		stops                                      []stop
	}
	var routes []route
	points := map[string]store.Feature{}
	err := eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		var p struct {
			Kind  any `json:"kind"`
			Title any `json:"title"`
			Route struct {
				Date, Members, Purpose, Summary any
				Stops                           []stop
			} `json:"route"`
		}
		if json.Unmarshal(f.Properties, &p) != nil {
			return nil
		}
		if str(p.Kind) != "route" {
			points[f.ID] = f
			return nil
		}
		r := route{
			id: f.ID, title: strings.TrimSpace(str(p.Title)), date: strings.TrimSpace(str(p.Route.Date)),
			members: strings.TrimSpace(str(p.Route.Members)), purpose: strings.TrimSpace(str(p.Route.Purpose)),
			summary: strings.TrimSpace(str(p.Route.Summary)), stops: p.Route.Stops,
		}
		if r.title == "" {
			r.title = "地质路线"
		}
		routes = append(routes, r)
		return nil
	})
	if err != nil {
		return 0, err
	}
	sort.SliceStable(routes, func(i, j int) bool {
		if routes[i].date != routes[j].date {
			return routes[i].date < routes[j].date
		}
		return naturalLess(routes[i].title, routes[j].title)
	})

	bw := bufio.NewWriter(out)
	bw.WriteString("\uFEFF")
	w := csv.NewWriter(bw)
	w.UseCRLF = true
	if err := w.Write(RouteHeader); err != nil {
		return 0, err
	}
	n := 0
	write := func(r []string) error {
		for i, c := range r {
			r[i] = defuse(c)
		}
		n++
		return w.Write(r)
	}
	for _, r := range routes {
		head := []string{r.title, r.date, r.members}
		if r.purpose != "" {
			if err := write(append(head[:3:3], "目的", "", "", "", "", "", "", "", r.purpose, "", r.id)); err != nil {
				return n, err
			}
		}
		seen := map[string]bool{}
		i := 0
		for _, s := range r.stops {
			id := strings.TrimSpace(str(s.Feature))
			f, ok := points[id]
			if id == "" || seen[id] || !ok {
				continue
			}
			seen[id] = true
			i++
			if err := write(append(head[:3:3], stopRow(i, f, str(s.Onward))...)); err != nil {
				return n, err
			}
		}
		if r.summary != "" {
			if err := write(append(head[:3:3], "小结", "", "", "", "", "", "", "", r.summary, "", r.id)); err != nil {
				return n, err
			}
		}
	}
	w.Flush()
	if err := w.Error(); err != nil {
		return n, err
	}
	return n, bw.Flush()
}

func stopRow(i int, f store.Feature, onward string) []string {
	var p map[string]any
	json.Unmarshal(f.Properties, &p)
	kind := str(p["kind"])
	if name, ok := kindNames[kind]; ok {
		kind = name
	}
	var lng, lat, alt string
	var g struct {
		Type        string    `json:"type"`
		Coordinates []float64 `json:"coordinates"`
	}
	if json.Unmarshal(f.Geometry, &g) == nil && g.Type == "Point" && len(g.Coordinates) >= 2 {
		lng = strconv.FormatFloat(g.Coordinates[0], 'f', 6, 64)
		lat = strconv.FormatFloat(g.Coordinates[1], 'f', 6, 64)
		if len(g.Coordinates) > 2 {
			alt = strconv.FormatFloat(g.Coordinates[2], 'f', 1, 64)
		}
	}
	var attitude string
	if dd, ok := p["dip_direction"].(float64); ok {
		if dip, ok := p["dip"].(float64); ok {
			attitude = fmt.Sprintf("%03.0f°∠%02.0f°", dd, dip)
		}
	}
	return []string{strconv.Itoa(i), strings.TrimSpace(str(p["title"])), kind, lng, lat, alt,
		strings.TrimSpace(str(p["lithology"])), attitude, strings.TrimSpace(str(p["note"])), strings.TrimSpace(onward), f.ID}
}

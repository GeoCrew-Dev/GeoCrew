package export

import (
	"bufio"
	"context"
	"encoding/csv"
	"encoding/json"
	"io"
	"sort"
	"strconv"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// WorkloadHeader is the column order of the daily workload table.
var WorkloadHeader = []string{"日期", "组员", "新建记录", "其中地质点", "修改次数", "样品", "照片", "轨迹(km)"}

// workTotal is the date column of each member's total row.
const workTotal = "合计"

type workDay struct {
	created, geoPoints, edits, samples, photos int
	trackM                                     float64
}

// Workload writes the team's field work per day and member (野外工作量统计):
// records each member created (from the first revision), later revisions
// they saved, samples and photos taken on records they created, and the
// length of tracks they started that day, days in zone. Each member also
// gets a total row. Deleted records are left out. Returns the row count.
func Workload(ctx context.Context, st store.Store, workspaceID string, zone *time.Location, out io.Writer) (int, error) {
	if _, err := st.Workspace(ctx, workspaceID); err != nil {
		return 0, err
	}
	names := memberNames(ctx, st, workspaceID)
	type key struct{ day, who string }
	work := map[key]*workDay{}
	at := func(day, who string) *workDay {
		k := key{day, who}
		if work[k] == nil {
			work[k] = &workDay{}
		}
		return work[k]
	}
	dayOf := func(t time.Time) string { return t.In(zone).Format("2006-01-02") }
	dayOfText := func(s string) (string, bool) {
		t, err := time.Parse(time.RFC3339, s)
		if err != nil {
			return "", false
		}
		return dayOf(t), true
	}

	err := eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		older, err := st.FeatureHistory(ctx, workspaceID, f.ID)
		if err != nil {
			return err
		}
		first := f
		if len(older) > 0 {
			first = older[len(older)-1]
		}
		for _, r := range append([]store.Feature{f}, older...) {
			if r.Version > first.Version && r.UpdatedBy != "" {
				at(dayOf(r.UpdatedAt), r.UpdatedBy).edits++
			}
		}
		who := first.UpdatedBy
		if who == "" {
			return nil
		}
		var p struct {
			Kind      string `json:"kind"`
			StartedAt string `json:"started_at"`
			Samples   []struct {
				No      any    `json:"no"`
				TakenAt string `json:"taken_at"`
			} `json:"samples"`
			PhotoInfo map[string]struct {
				TakenAt string `json:"taken_at"`
			} `json:"photo_info"`
		}
		_ = json.Unmarshal(f.Properties, &p)
		created := dayOf(first.UpdatedAt)
		if p.Kind == "track" {
			day := created
			if d, ok := dayOfText(p.StartedAt); ok {
				day = d
			}
			at(day, who).trackM += lineMeters(f.Geometry)
		} else {
			w := at(created, who)
			w.created++
			if p.Kind == "geo_point" {
				w.geoPoints++
			}
		}
		for _, s := range p.Samples {
			if str(s.No) == "" {
				continue
			}
			if d, ok := dayOfText(s.TakenAt); ok {
				at(d, who).samples++
			}
		}
		for _, ph := range p.PhotoInfo {
			if d, ok := dayOfText(ph.TakenAt); ok {
				at(d, who).photos++
			}
		}
		return nil
	})
	if err != nil {
		return 0, err
	}

	name := func(id string) string {
		if n := names[id]; n != "" {
			return n
		}
		return "已离开的组员"
	}
	keys := make([]key, 0, len(work))
	totals := map[string]*workDay{}
	for k, w := range work {
		keys = append(keys, k)
		t := totals[k.who]
		if t == nil {
			t = &workDay{}
			totals[k.who] = t
		}
		t.created += w.created
		t.geoPoints += w.geoPoints
		t.edits += w.edits
		t.samples += w.samples
		t.photos += w.photos
		t.trackM += w.trackM
	}
	sort.Slice(keys, func(i, j int) bool {
		if keys[i].day != keys[j].day {
			return keys[i].day < keys[j].day
		}
		return naturalLess(name(keys[i].who), name(keys[j].who))
	})
	whos := make([]string, 0, len(totals))
	for who := range totals {
		whos = append(whos, who)
	}
	sort.Slice(whos, func(i, j int) bool { return naturalLess(name(whos[i]), name(whos[j])) })

	row := func(day, who string, w *workDay) []string {
		return []string{day, defuse(name(who)), strconv.Itoa(w.created), strconv.Itoa(w.geoPoints), strconv.Itoa(w.edits),
			strconv.Itoa(w.samples), strconv.Itoa(w.photos), fixed(w.trackM/1000, 2)}
	}
	bw := bufio.NewWriter(out)
	bw.WriteString("\uFEFF")
	cw := csv.NewWriter(bw)
	cw.UseCRLF = true
	if err := cw.Write(WorkloadHeader); err != nil {
		return 0, err
	}
	n := 0
	for _, k := range keys {
		if err := cw.Write(row(k.day, k.who, work[k])); err != nil {
			return n, err
		}
		n++
	}
	for _, who := range whos {
		if err := cw.Write(row(workTotal, who, totals[who])); err != nil {
			return n, err
		}
		n++
	}
	cw.Flush()
	if err := cw.Error(); err != nil {
		return n, err
	}
	return n, bw.Flush()
}

// lineMeters is the length of a LineString or MultiLineString in metres.
func lineMeters(raw json.RawMessage) float64 {
	var g struct {
		Type        string          `json:"type"`
		Coordinates json.RawMessage `json:"coordinates"`
	}
	if json.Unmarshal(raw, &g) != nil {
		return 0
	}
	var lines [][][]float64
	switch g.Type {
	case "LineString":
		var l [][]float64
		if json.Unmarshal(g.Coordinates, &l) != nil {
			return 0
		}
		lines = [][][]float64{l}
	case "MultiLineString":
		if json.Unmarshal(g.Coordinates, &lines) != nil {
			return 0
		}
	}
	m := 0.0
	for _, l := range lines {
		for i := 1; i < len(l); i++ {
			if len(l[i-1]) >= 2 && len(l[i]) >= 2 {
				m += haversine(l[i-1][0], l[i-1][1], l[i][0], l[i][1])
			}
		}
	}
	return m
}

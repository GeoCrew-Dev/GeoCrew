package export

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
	"unicode"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
)

// Issue codes of the record check, shared with the Android client's
// "待核对" list. Codes are stable; messages are for display only.
const (
	IssueDuplicateNumber = "duplicate_number"
	IssueDuplicateSample = "duplicate_sample"
	IssueNoPosition      = "no_position"
	IssuePoorAccuracy    = "poor_accuracy"
	IssueFarAway         = "far_away"
	IssueMissingStop     = "missing_stop"
	// IssueRedo: a leader sent the record back (review.state = redo, protocol §6.9).
	IssueRedo = "redo"
	// IssueIncomplete: a geological point lacks what the workspace's
	// record_rules require (shared settings, protocol §2.2).
	IssueIncomplete = "incomplete"
)

var issueOrder = map[string]int{
	IssueDuplicateNumber: 0, IssueDuplicateSample: 1, IssueNoPosition: 2,
	IssuePoorAccuracy: 3, IssueFarAway: 4, IssueMissingStop: 5, IssueIncomplete: 6, IssueRedo: -1,
}

// Issue is one thing worth a second look on one record.
type Issue struct {
	Feature string `json:"feature"`
	Title   string `json:"title,omitempty"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

// CheckOptions are the thresholds of [Checks]; zero values take the defaults.
type CheckOptions struct {
	AccuracyLimitM float64 // fixes poorer than this are reported; default 30 m
	FarKm          float64 // records further than this from the middle of the rest; default 50 km
}

var (
	locatedKinds  = map[string]bool{"point": true, "geo_point": true, "line": true, "polygon": true, "track": true, "photo": true, "borehole": true}
	numberedKinds = map[string]bool{"point": true, "geo_point": true, "photo": true, "section": true, "route": true, "borehole": true}
)

const minForOutlier = 5

// Checks finds the slips that are cheap to fix in the field: station
// numbers used twice, sample numbers used twice (lab results would land on
// the wrong bag), records that should have a position and have none, poor
// fixes, records far away from all the others (mistyped or swapped
// coordinates) and routes whose points were deleted. Issues come ordered by
// record title, then by code.
func Checks(ctx context.Context, st store.Store, workspaceID string, opt CheckOptions) ([]Issue, error) {
	if _, err := st.Workspace(ctx, workspaceID); err != nil {
		return nil, err
	}
	if opt.AccuracyLimitM <= 0 {
		opt.AccuracyLimitM = 30
	}
	if opt.FarKm <= 0 {
		opt.FarKm = 50
	}
	type rec struct {
		id, kind, title string
		samples         []string
		accuracy        *float64
		stops           []string
		lon, lat        float64
		located         bool
		redo            bool
		redoNote        string
		missing         []string
	}
	rules := recordRules(ctx, st, workspaceID)
	var recs []rec
	err := eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		var p struct {
			Kind     any `json:"kind"`
			Title    any `json:"title"`
			Accuracy any `json:"accuracy_m"`
			Samples  []struct {
				No any `json:"no"`
			} `json:"samples"`
			Route struct {
				Stops []struct {
					Feature any `json:"feature"`
				} `json:"stops"`
			} `json:"route"`
			Review struct {
				State any `json:"state"`
				Note  any `json:"note"`
			} `json:"review"`
			Lithology    any   `json:"lithology"`
			Note         any   `json:"note"`
			Dip          any   `json:"dip"`
			DipDirection any   `json:"dip_direction"`
			Strike       any   `json:"strike"`
			Photos       []any `json:"photos"`
		}
		if json.Unmarshal(f.Properties, &p) != nil {
			return nil
		}
		r := rec{id: f.ID, kind: str(p.Kind), title: strings.TrimSpace(str(p.Title))}
		r.redo, r.redoNote = str(p.Review.State) == "redo", strings.TrimSpace(str(p.Review.Note))
		for _, s := range p.Samples {
			if no := strings.TrimSpace(str(s.No)); no != "" {
				r.samples = append(r.samples, no)
			}
		}
		if a, ok := num(p.Accuracy); ok {
			r.accuracy = &a
		}
		for _, s := range p.Route.Stops {
			if id := str(s.Feature); id != "" {
				r.stops = append(r.stops, id)
			}
		}
		if lon, lat, _ := firstPosition(f.Geometry); lon != "" {
			x, e1 := strconv.ParseFloat(lon, 64)
			y, e2 := strconv.ParseFloat(lat, 64)
			if e1 == nil && e2 == nil {
				r.lon, r.lat, r.located = x, y, true
			}
		}
		if r.kind == "geo_point" && rules.active() {
			_, hasDip := num(p.Dip)
			_, hasDD := num(p.DipDirection)
			_, hasStrike := num(p.Strike)
			r.missing = rules.missing(pointFacts{
				lithology: strings.TrimSpace(str(p.Lithology)) != "",
				attitude:  hasDip && (hasDD || hasStrike),
				note:      strings.TrimSpace(str(p.Note)) != "",
				sample:    len(r.samples) > 0,
				photos:    len(p.Photos),
			})
		}
		recs = append(recs, r)
		return nil
	})
	if err != nil {
		return nil, err
	}

	var out []Issue
	add := func(r rec, code, msg string) { out = append(out, Issue{r.id, r.title, code, msg}) }
	live := map[string]bool{}
	for _, r := range recs {
		live[r.id] = true
	}

	numbers := map[string][]rec{}
	samples := map[string][]rec{}
	for _, r := range recs {
		if numberedKinds[r.kind] && strings.IndexFunc(r.title, unicode.IsDigit) >= 0 {
			k := strings.ToLower(r.title)
			numbers[k] = append(numbers[k], r)
		}
		for _, no := range r.samples {
			k := strings.ToLower(no)
			samples[k] = append(samples[k], r)
		}
	}
	for _, r := range recs {
		if group := numbers[strings.ToLower(r.title)]; len(group) > 1 && numberedKinds[r.kind] && strings.IndexFunc(r.title, unicode.IsDigit) >= 0 {
			add(r, IssueDuplicateNumber, fmt.Sprintf("编号「%s」还用在另外 %d 条记录上", r.title, len(group)-1))
		}
		seen := map[string]bool{}
		for _, no := range r.samples {
			k := strings.ToLower(no)
			if seen[k] || len(samples[k]) < 2 {
				continue
			}
			seen[k] = true
			var others []string
			dup := map[string]bool{r.id: true}
			for _, o := range samples[k] {
				if !dup[o.id] {
					dup[o.id] = true
					others = append(others, orUnnamed(o.title))
				}
			}
			if len(others) == 0 {
				add(r, IssueDuplicateSample, fmt.Sprintf("样品编号「%s」在这条记录里用了两次", no))
			} else {
				add(r, IssueDuplicateSample, fmt.Sprintf("样品编号「%s」也在 %s", no, strings.Join(others, "、")))
			}
		}
		if r.redo {
			if r.redoNote != "" {
				add(r, IssueRedo, "组长退回："+r.redoNote)
			} else {
				add(r, IssueRedo, "组长退回，需要补充或改正")
			}
		}
		if locatedKinds[r.kind] && !r.located {
			add(r, IssueNoPosition, "没有位置，地图和导出里都不会出现")
		}
		if r.accuracy != nil && *r.accuracy > opt.AccuracyLimitM {
			add(r, IssuePoorAccuracy, fmt.Sprintf("定位精度约 ±%.0f 米", *r.accuracy))
		}
		if len(r.missing) > 0 {
			add(r, IssueIncomplete, "本工程要求填："+strings.Join(r.missing, "、"))
		}
		if r.kind == "route" {
			gone := 0
			for _, id := range r.stops {
				if !live[id] {
					gone++
				}
			}
			if gone > 0 {
				add(r, IssueMissingStop, fmt.Sprintf("有 %d 个路线点已被删除", gone))
			}
		}
	}

	// Far from the median position of the rest.
	var lons, lats []float64
	for _, r := range recs {
		if r.located {
			lons, lats = append(lons, r.lon), append(lats, r.lat)
		}
	}
	if len(lons) >= minForOutlier {
		mlon, mlat := median(lons), median(lats)
		var ds []float64
		for _, r := range recs {
			if r.located {
				ds = append(ds, haversine(mlon, mlat, r.lon, r.lat))
			}
		}
		typical := median(ds)
		for _, r := range recs {
			if !r.located {
				continue
			}
			if d := haversine(mlon, mlat, r.lon, r.lat); d > opt.FarKm*1000 && d > typical*10 {
				add(r, IssueFarAway, fmt.Sprintf("离其他记录约 %.0f 千米，坐标可能输错了", d/1000))
			}
		}
	}

	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Feature != out[j].Feature {
			if out[i].Title != out[j].Title {
				return naturalLess(out[i].Title, out[j].Title)
			}
			return out[i].Feature < out[j].Feature
		}
		return issueOrder[out[i].Code] < issueOrder[out[j].Code]
	})
	return out, nil
}

func orUnnamed(s string) string {
	if s == "" {
		return "未命名"
	}
	return s
}

func median(v []float64) float64 {
	s := append([]float64(nil), v...)
	sort.Float64s(s)
	n := len(s)
	if n%2 == 1 {
		return s[n/2]
	}
	return (s[n/2-1] + s[n/2]) / 2
}

// haversine is the great-circle distance in metres on the mean Earth sphere.
func haversine(lon1, lat1, lon2, lat2 float64) float64 {
	const r = 6371008.8
	rad := math.Pi / 180
	dlat, dlon := (lat2-lat1)*rad, (lon2-lon1)*rad
	a := math.Sin(dlat/2)*math.Sin(dlat/2) + math.Cos(lat1*rad)*math.Cos(lat2*rad)*math.Sin(dlon/2)*math.Sin(dlon/2)
	return 2 * r * math.Asin(math.Min(1, math.Sqrt(a)))
}

// rules is the part of the workspace's record_rules (protocol §2.2) the check uses.
type rules struct {
	Required  []string `json:"required"`
	MinPhotos int      `json:"min_photos"`
}

type pointFacts struct {
	lithology, attitude, note, sample bool
	photos                            int
}

var requiredLabels = []struct{ id, label string }{
	{"lithology", "岩性"}, {"attitude", "产状"}, {"note", "描述"}, {"sample", "样品"},
}

func (r rules) active() bool { return len(r.Required) > 0 || r.MinPhotos > 0 }

// missing lists, in words, what a point lacks; unknown items are ignored.
func (r rules) missing(f pointFacts) []string {
	need := map[string]bool{}
	for _, id := range r.Required {
		need[id] = true
	}
	have := map[string]bool{"lithology": f.lithology, "attitude": f.attitude, "note": f.note, "sample": f.sample}
	var out []string
	for _, it := range requiredLabels {
		if need[it.id] && !have[it.id] {
			out = append(out, it.label)
		}
	}
	if r.MinPhotos > 0 && f.photos < r.MinPhotos {
		if r.MinPhotos == 1 {
			out = append(out, "照片")
		} else {
			out = append(out, fmt.Sprintf("照片（至少 %d 张，现有 %d 张）", r.MinPhotos, f.photos))
		}
	}
	return out
}

// recordRules reads record_rules from the shared settings; none or unreadable means no rules.
func recordRules(ctx context.Context, st store.Store, workspaceID string) rules {
	raw, _, err := st.Settings(ctx, workspaceID)
	if err != nil || len(raw) == 0 {
		return rules{}
	}
	var s struct {
		RecordRules rules `json:"record_rules"`
	}
	if json.Unmarshal(raw, &s) != nil {
		return rules{}
	}
	if s.RecordRules.MinPhotos > 20 {
		s.RecordRules.MinPhotos = 20
	}
	return s.RecordRules
}

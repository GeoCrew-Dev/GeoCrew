package export

import (
	"bufio"
	"context"
	"encoding/csv"
	"encoding/json"
	"io"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// SampleHeader is the column order of the sample register, the same as the
// Android client's "样品清单" export.
var SampleHeader = []string{"样品编号", "样品类型", "所在记录", "经度", "纬度", "高程", "岩性", "采样时间", "样品备注", "记录ID"}

type sampleRow struct {
	no      string
	fields  []string
	results map[string]string
}

// Samples writes the workspace's sample register (protocol v0 §6.4) as CSV,
// one row per sample in natural number order, and returns the row count.
// Times are shown in zone. The file starts with a UTF-8 BOM for spreadsheet
// programs, and cells that a spreadsheet would run as a formula are prefixed
// with an apostrophe.
func Samples(ctx context.Context, st store.Store, workspaceID string, zone *time.Location, out io.Writer) (int, error) {
	if _, err := st.Workspace(ctx, workspaceID); err != nil {
		return 0, err
	}
	var rows []sampleRow
	err := eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		var p struct {
			Title     any              `json:"title"`
			Lithology any              `json:"lithology"`
			Samples   []map[string]any `json:"samples"`
		}
		if json.Unmarshal(f.Properties, &p) != nil {
			return nil // not an object: nothing to list
		}
		lon, lat, alt := firstPosition(f.Geometry)
		for _, s := range p.Samples {
			no := strings.TrimSpace(str(s["no"]))
			if no == "" {
				continue
			}
			taken := ""
			if t, err := time.Parse(time.RFC3339Nano, str(s["taken_at"])); err == nil {
				taken = t.In(zone).Format("2006-01-02 15:04")
			}
			rows = append(rows, sampleRow{no, []string{no, str(s["type"]), str(p.Title), lon, lat, alt, str(p.Lithology), taken, str(s["note"]), f.ID}, sampleResults(s["results"])})
		}
		return nil
	})
	if err != nil {
		return 0, err
	}
	sort.SliceStable(rows, func(i, j int) bool { return naturalLess(rows[i].no, rows[j].no) })
	// Lab results (§6.4), one column per analysed item, after the fixed columns.
	seen := map[string]bool{}
	var extra []string
	for _, r := range rows {
		for k := range r.results {
			if !seen[k] {
				seen[k] = true
				extra = append(extra, k)
			}
		}
	}
	sort.Slice(extra, func(i, j int) bool { return naturalLess(extra[i], extra[j]) })

	bw := bufio.NewWriter(out)
	bw.WriteString("\uFEFF")
	w := csv.NewWriter(bw)
	w.UseCRLF = true
	if err := w.Write(append(append([]string{}, SampleHeader...), extra...)); err != nil {
		return 0, err
	}
	for _, r := range rows {
		for _, k := range extra {
			r.fields = append(r.fields, r.results[k])
		}
		for i, c := range r.fields {
			r.fields[i] = defuse(c)
		}
		if err := w.Write(r.fields); err != nil {
			return 0, err
		}
	}
	w.Flush()
	if err := w.Error(); err != nil {
		return len(rows), err
	}
	return len(rows), bw.Flush()
}

// sampleResults reads a sample's lab results: numbers, or "<limit" for values below the detection limit.
func sampleResults(v any) map[string]string {
	m, ok := v.(map[string]any)
	if !ok {
		return nil
	}
	out := map[string]string{}
	for k, x := range m {
		switch y := x.(type) {
		case float64:
			out[k] = strconv.FormatFloat(y, 'f', -1, 64)
		case string:
			if t := strings.TrimSpace(y); strings.HasPrefix(t, "<") {
				out[k] = t
			}
		}
	}
	return out
}

func str(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

// firstPosition returns the first coordinate pair (and height) of a GeoJSON
// geometry as text, or empty strings when there is none.
func firstPosition(raw json.RawMessage) (lon, lat, alt string) {
	var g struct {
		Coordinates json.RawMessage   `json:"coordinates"`
		Geometries  []json.RawMessage `json:"geometries"`
	}
	if json.Unmarshal(raw, &g) != nil {
		return
	}
	if len(g.Coordinates) == 0 && len(g.Geometries) > 0 {
		return firstPosition(g.Geometries[0])
	}
	c := g.Coordinates
	for depth := 0; depth < 4; depth++ {
		var nums []json.Number
		if json.Unmarshal(c, &nums) == nil {
			if len(nums) >= 2 {
				lon, lat = nums[0].String(), nums[1].String()
				if len(nums) > 2 {
					alt = nums[2].String()
				}
			}
			return
		}
		var nested []json.RawMessage
		if json.Unmarshal(c, &nested) != nil || len(nested) == 0 {
			return
		}
		c = nested[0]
	}
	return
}

// defuse keeps spreadsheet programs from running a cell as a formula.
// Negative numbers are left alone.
func defuse(s string) string {
	if s == "" {
		return s
	}
	switch s[0] {
	case '=', '+', '@', '\t', '\r':
		return "'" + s
	case '-':
		if len(s) > 1 && (s[1] >= '0' && s[1] <= '9' || s[1] == '.') {
			return s
		}
		return "'" + s
	}
	return s
}

// naturalLess orders "D2-1" before "D10-1": digit runs compare as numbers,
// letters without case.
func naturalLess(a, b string) bool {
	x, y := parts(strings.ToLower(a)), parts(strings.ToLower(b))
	for i := 0; i < len(x) && i < len(y); i++ {
		p, q := x[i], y[i]
		if p == q {
			continue
		}
		if isDigits(p) && isDigits(q) {
			p, q = strings.TrimLeft(p, "0"), strings.TrimLeft(q, "0")
			if len(p) != len(q) {
				return len(p) < len(q)
			}
		}
		if p != q {
			return p < q
		}
	}
	return len(x) < len(y)
}

func parts(s string) []string {
	var out []string
	start := 0
	for i, r := range s {
		if i > start && unicode.IsDigit(r) != unicode.IsDigit(rune(s[start])) {
			out = append(out, s[start:i])
			start = i
		}
	}
	if start < len(s) {
		out = append(out, s[start:])
	}
	return out
}

func isDigits(s string) bool {
	for _, r := range s {
		if !unicode.IsDigit(r) {
			return false
		}
	}
	return s != ""
}

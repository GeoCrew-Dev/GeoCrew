package api

import (
	"bytes"
	"context"
	"encoding/csv"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/export"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/mcp"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// mcpInstructions tells an AI client what the tools are for.
const mcpInstructions = `GeoCrew (同勘) workspace of a field geology team. Records are GeoJSON features: geological points (geo_point) with lithology and attitudes (strike / dip_direction / dip, degrees), tracks, lines, polygons, measured sections, routes and boreholes. Coordinates are WGS 84 longitude, latitude. All tools only read; changes are made in the GeoCrew app. Answer in the user's language; the team usually writes Chinese.`

// mcpEndpoint serves read-only MCP tools for one workspace (protocol v0
// §11), with a member's token or, preferably, a view link of theirs.
// Writing stays in the app, where actions carry risk classes and
// confirmations (actions.md).
func (s *Server) mcpEndpoint(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	tools := s.mcpTools(ws)
	instructions := mcpInstructions
	if m := member(r); m.AI {
		tools = append(tools, s.mcpWriteTools(ws, m)...)
		instructions = strings.Replace(instructions, "All tools only read; changes are made in the GeoCrew app.",
			"This credential may also post messages and set task status as the member who made it; confirm with the user before every change. Everything else is changed in the GeoCrew app.", 1)
	}
	srv := &mcp.Server{Name: "geocrew", Version: s.Version, Instructions: instructions, Tools: tools,
		Log: func(tool string, err error) { s.logger().Error("mcp tool", "tool", tool, "err", err) }}
	srv.ServeHTTP(w, r)
}

func schema(props string, required ...string) json.RawMessage {
	req, _ := json.Marshal(required)
	if len(required) == 0 {
		req = []byte("[]")
	}
	return json.RawMessage(`{"type":"object","properties":{` + props + `},"required":` + string(req) + `,"additionalProperties":false}`)
}

func (s *Server) mcpTools(ws string) []mcp.Tool {
	return []mcp.Tool{
		{
			Name: "workspace_summary", Title: "工作组概况",
			Description: "Workspace name, members with role and last activity, and how many records of each kind there are.",
			InputSchema: schema(``),
			Call:        func(ctx context.Context, _ json.RawMessage) (any, error) { return s.mcpSummary(ctx, ws) },
		},
		{
			Name: "search_records", Title: "查找记录",
			Description: "Find records by words in title, lithology, note or sample numbers, by kind, by who changed them last, since when, and near a place or another record (by first position). Newest first, or nearest first when near is given.",
			InputSchema: schema(`"query":{"type":"string","description":"words that must all appear (title, lithology, note, sample no.)"},` +
				`"kind":{"type":"string","description":"e.g. geo_point, track, line, polygon, section, route, borehole"},` +
				`"member":{"type":"string","description":"nickname of who changed it last"},` +
				`"since":{"type":"string","description":"date or time, e.g. 2026-11-24 or RFC 3339"},` +
				`"near_record":{"type":"string","description":"id of a record; finds records around it"},` +
				`"near_lon":{"type":"number","minimum":-180,"maximum":180},"near_lat":{"type":"number","minimum":-90,"maximum":90},` +
				`"radius_m":{"type":"number","exclusiveMinimum":0,"maximum":100000,"default":1000},` +
				`"limit":{"type":"integer","minimum":1,"maximum":100,"default":20}`),
			Call: func(ctx context.Context, args json.RawMessage) (any, error) { return s.mcpSearch(ctx, ws, args) },
		},
		{
			Name: "get_record", Title: "查看记录",
			Description: "One record in full: geometry, all properties and sync metadata, who changed it last, how many earlier versions its history keeps and the team's comments on it (up to the last 50). Takes the record's id or its name (e.g. D012) when only one record has that name.",
			InputSchema: schema(`"id":{"type":"string","description":"record id, or a record name such as D012"}`, "id"),
			Call:        func(ctx context.Context, args json.RawMessage) (any, error) { return s.mcpRecord(ctx, ws, args) },
		},
		{
			Name: "review_issues", Title: "待核对",
			Description: "Problems the server finds in the records: duplicate point or sample numbers, missing or poor positions, outliers, broken routes, records sent back by the leader and items the project's record rules require.",
			InputSchema: schema(``),
			Call: func(ctx context.Context, _ json.RawMessage) (any, error) {
				issues, err := export.Checks(ctx, s.Store, ws, export.CheckOptions{})
				if issues == nil {
					issues = []export.Issue{}
				}
				return map[string]any{"issues": issues}, err
			},
		},
		{
			Name: "reference_at", Title: "参考图层查询",
			Description: "What the leader's shared reference layers (earlier geological maps, licence areas) say at a place or a record: the areas containing it, e.g. the map unit, and lines such as faults within reach_m, nearest first.",
			InputSchema: schema(`"record":{"type":"string","description":"id of a record; its first position is used"},` +
				`"lon":{"type":"number","minimum":-180,"maximum":180},"lat":{"type":"number","minimum":-90,"maximum":90},` +
				`"reach_m":{"type":"number","exclusiveMinimum":0,"maximum":5000,"default":200}`),
			Call: func(ctx context.Context, args json.RawMessage) (any, error) { return s.mcpReferenceAt(ctx, ws, args) },
		},
		{
			Name: "workload", Title: "工作量",
			Description: "Field work per day and member: records created (geological points among them), edits, samples, photos and track kilometres, with a total row per member. Days are in the given time zone (default UTC+8).",
			InputSchema: schema(`"from":{"type":"string","description":"first day, e.g. 2026-11-20"},` +
				`"to":{"type":"string","description":"last day, inclusive"},` +
				`"tz_offset_minutes":{"type":"integer","minimum":-720,"maximum":840,"default":480}`),
			Call: func(ctx context.Context, args json.RawMessage) (any, error) { return s.mcpWorkload(ctx, ws, args) },
		},
		{
			Name: "recent_messages", Title: "组内消息",
			Description: "The latest workspace messages and record comments, newest last, including calls for help and roll calls.",
			InputSchema: schema(`"limit":{"type":"integer","minimum":1,"maximum":100,"default":30}`),
			Call:        func(ctx context.Context, args json.RawMessage) (any, error) { return s.mcpMessages(ctx, ws, args) },
		},
		{
			Name: "tasks", Title: "任务指派",
			Description: "Records the leader assigned to members (protocol §6.1), e.g. sampling points to visit: who has what, todo / doing / done, with per-member counts. Filter by member nickname and status.",
			InputSchema: schema(`"member":{"type":"string","description":"nickname of the assignee"},` +
				`"status":{"type":"string","enum":["todo","doing","done"]},` +
				`"limit":{"type":"integer","minimum":1,"maximum":200,"default":50}`),
			Call: func(ctx context.Context, args json.RawMessage) (any, error) { return s.mcpTasks(ctx, ws, args) },
		},
		{
			Name: "team_positions", Title: "组员位置",
			Description: "Where the team is: every member's last shared live position (WGS 84) from the last 15 minutes with accuracy, its age in seconds and whether the member is online, newest first; plus members online without a position. Members share positions only while they choose to in the app.",
			InputSchema: schema(``),
			Call:        func(ctx context.Context, _ json.RawMessage) (any, error) { return s.mcpPositions(ctx, ws) },
		},
	}
}

func args[T any](raw json.RawMessage) (T, error) {
	var v T
	if err := json.Unmarshal(raw, &v); err != nil {
		return v, mcp.BadArgs("arguments do not match the schema: %v", err)
	}
	return v, nil
}

func clampLimit(n, def, max int) int {
	if n <= 0 {
		return def
	}
	return min(n, max)
}

type mcpMember struct {
	Nickname string     `json:"nickname"`
	Role     store.Role `json:"role"`
	LastSeen *time.Time `json:"last_seen,omitempty"`
}

func (s *Server) mcpSummary(ctx context.Context, ws string) (any, error) {
	w, err := s.Store.Workspace(ctx, ws)
	if err != nil {
		return nil, err
	}
	ms, err := s.Store.Members(ctx, ws)
	if err != nil {
		return nil, err
	}
	members := []mcpMember{}
	for _, m := range ms {
		if !m.Revoked {
			members = append(members, mcpMember{m.Nickname, m.Role, m.LastSeen})
		}
	}
	kinds := map[string]int{}
	var total, samples int
	var last time.Time
	err = export.EachRecord(ctx, s.Store, ws, func(rec export.Record) error {
		total++
		kinds[rec.Kind]++
		samples += len(rec.Samples)
		if rec.UpdatedAt.After(last) {
			last = rec.UpdatedAt
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	out := map[string]any{
		"name": w.Name, "archived": w.ArchivedAt != nil, "members": members,
		"records": total, "records_by_kind": kinds, "samples": samples,
	}
	if !last.IsZero() {
		out["last_change"] = last
	}
	return out, nil
}

type mcpRecordRow struct {
	ID        string    `json:"id"`
	Kind      string    `json:"kind"`
	Title     string    `json:"title,omitempty"`
	Lithology string    `json:"lithology,omitempty"`
	Note      string    `json:"note,omitempty"`
	Samples   []string  `json:"samples,omitempty"`
	Lon       *float64  `json:"lon,omitempty"`
	Lat       *float64  `json:"lat,omitempty"`
	UpdatedAt time.Time `json:"updated_at"`
	UpdatedBy string    `json:"updated_by,omitempty"`
	DistanceM *float64  `json:"distance_m,omitempty"`
}

func clip(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n]) + "…"
}

func parseSince(v string) (time.Time, error) {
	v = strings.TrimSpace(v)
	if v == "" {
		return time.Time{}, nil
	}
	for _, layout := range []string{time.RFC3339, "2006-01-02T15:04", "2006-01-02"} {
		if t, err := time.Parse(layout, v); err == nil {
			return t, nil
		}
	}
	return time.Time{}, mcp.BadArgs("since must be a date like 2026-11-24 or an RFC 3339 time")
}

func (s *Server) mcpSearch(ctx context.Context, ws string, raw json.RawMessage) (any, error) {
	a, err := args[struct {
		Query  string   `json:"query"`
		Kind   string   `json:"kind"`
		Member string   `json:"member"`
		Since  string   `json:"since"`
		Limit  int      `json:"limit"`
		Near   string   `json:"near_record"`
		Lon    *float64 `json:"near_lon"`
		Lat    *float64 `json:"near_lat"`
		Radius float64  `json:"radius_m"`
	}](raw)
	if err != nil {
		return nil, err
	}
	since, err := parseSince(a.Since)
	if err != nil {
		return nil, err
	}
	if (a.Lon == nil) != (a.Lat == nil) {
		return nil, mcp.BadArgs("give both near_lon and near_lat")
	}
	if a.Near != "" {
		rec, err := export.LiveRecord(ctx, s.Store, ws, a.Near)
		if errors.Is(err, store.ErrNotFound) {
			return nil, mcp.BadArgs("no record %q here", a.Near)
		}
		if err != nil {
			return nil, err
		}
		if rec.Lon == nil {
			return nil, mcp.BadArgs("record %q has no position", a.Near)
		}
		a.Lon, a.Lat = rec.Lon, rec.Lat
	}
	near := a.Lon != nil
	radius := a.Radius
	if radius <= 0 {
		radius = 1000
	}
	if radius > 100000 {
		return nil, mcp.BadArgs("radius_m is at most 100000")
	}
	words := strings.Fields(strings.ToLower(a.Query))
	who := strings.TrimSpace(a.Member)
	var rows []mcpRecordRow
	err = export.EachRecord(ctx, s.Store, ws, func(rec export.Record) error {
		if a.Kind != "" && rec.Kind != a.Kind || who != "" && rec.UpdatedBy != who ||
			!since.IsZero() && rec.UpdatedAt.Before(since) {
			return nil
		}
		var dist *float64
		if near {
			if rec.Lon == nil || rec.ID == a.Near {
				return nil
			}
			d := math.Round(export.DistanceM(*a.Lon, *a.Lat, *rec.Lon, *rec.Lat))
			if d > radius {
				return nil
			}
			dist = &d
		}
		text := strings.ToLower(rec.Title + " " + rec.Lithology + " " + rec.Note + " " + strings.Join(rec.Samples, " "))
		for _, w := range words {
			if !strings.Contains(text, w) {
				return nil
			}
		}
		rows = append(rows, mcpRecordRow{
			ID: rec.ID, Kind: rec.Kind, Title: rec.Title, Lithology: rec.Lithology, Note: clip(rec.Note, 160),
			Samples: rec.Samples, Lon: rec.Lon, Lat: rec.Lat, UpdatedAt: rec.UpdatedAt, UpdatedBy: rec.UpdatedBy, DistanceM: dist,
		})
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.SliceStable(rows, func(i, j int) bool {
		if near {
			return *rows[i].DistanceM < *rows[j].DistanceM
		}
		return rows[i].UpdatedAt.After(rows[j].UpdatedAt)
	})
	limit := clampLimit(a.Limit, 20, 100)
	matched := len(rows)
	if matched > limit {
		rows = rows[:limit]
	}
	if rows == nil {
		rows = []mcpRecordRow{}
	}
	return map[string]any{"matched": matched, "records": rows}, nil
}

func (s *Server) mcpRecord(ctx context.Context, ws string, raw json.RawMessage) (any, error) {
	a, err := args[struct {
		ID string `json:"id"`
	}](raw)
	if err != nil {
		return nil, err
	}
	if a.ID == "" {
		return nil, mcp.BadArgs("id is required")
	}
	rec, err := export.LiveRecord(ctx, s.Store, ws, a.ID)
	if errors.Is(err, store.ErrNotFound) {
		// People and models say "D012"; take a unique title as well.
		var hits []export.Record
		err = export.EachRecord(ctx, s.Store, ws, func(r export.Record) error {
			if r.Title != "" && strings.EqualFold(r.Title, strings.TrimSpace(a.ID)) {
				hits = append(hits, r)
			}
			return nil
		})
		switch {
		case err != nil:
			return nil, err
		case len(hits) == 1:
			rec = hits[0]
		case len(hits) > 1:
			return nil, mcp.BadArgs("%d records are named %q; use search_records and pass an id", len(hits), a.ID)
		default:
			return nil, mcp.BadArgs("no record %q here; search_records lists the ids", a.ID)
		}
	} else if err != nil {
		return nil, err
	}
	hist, err := s.Store.FeatureHistory(ctx, ws, rec.ID)
	if err != nil {
		return nil, err
	}
	comments, err := s.mcpComments(ctx, ws, rec.ID)
	if err != nil {
		return nil, err
	}
	if len(comments) > 50 {
		comments = comments[len(comments)-50:]
	}
	return map[string]any{"feature": toWire(rec.Feature), "updated_by": rec.UpdatedBy, "earlier_versions": len(hist), "comments": comments}, nil
}

// mcpComments lists the live comments of ws, oldest first; with feature
// set, only those about that record.
func (s *Server) mcpComments(ctx context.Context, ws, feature string) ([]mcpMessage, error) {
	all := []mcpMessage{}
	var since int64
	for {
		cs, more, err := s.Store.Comments(ctx, ws, since, 500)
		if err != nil {
			return nil, err
		}
		for _, c := range cs {
			since = c.Seq
			if c.Deleted || (feature != "" && c.FeatureID != feature) {
				continue
			}
			all = append(all, mcpMessage{c.Nickname, c.Body, c.CreatedAt, c.FeatureID, c.Urgent, c.RollCall})
		}
		if !more || len(cs) == 0 {
			return all, nil
		}
	}
}

type mcpMessage struct {
	From     string    `json:"from"`
	Body     string    `json:"body"`
	At       time.Time `json:"at"`
	Record   string    `json:"record,omitempty"`
	Urgent   bool      `json:"urgent,omitempty"`
	RollCall bool      `json:"roll_call,omitempty"`
}

func (s *Server) mcpMessages(ctx context.Context, ws string, raw json.RawMessage) (any, error) {
	a, err := args[struct {
		Limit int `json:"limit"`
	}](raw)
	if err != nil {
		return nil, err
	}
	limit := clampLimit(a.Limit, 30, 100)
	all, err := s.mcpComments(ctx, ws, "")
	if err != nil {
		return nil, err
	}
	if len(all) > limit {
		all = all[len(all)-limit:]
	}
	if all == nil {
		all = []mcpMessage{}
	}
	return map[string]any{"messages": all}, nil
}

func (s *Server) mcpWorkload(ctx context.Context, ws string, raw json.RawMessage) (any, error) {
	a, err := args[struct {
		From   string `json:"from"`
		To     string `json:"to"`
		Offset *int   `json:"tz_offset_minutes"`
	}](raw)
	if err != nil {
		return nil, err
	}
	offset := 480
	if a.Offset != nil {
		offset = *a.Offset
	}
	if offset < -720 || offset > 840 {
		return nil, mcp.BadArgs("tz_offset_minutes must be between -720 and 840")
	}
	day := func(name, v string) (string, error) {
		v = strings.TrimSpace(v)
		if v == "" {
			return "", nil
		}
		if _, err := time.Parse("2006-01-02", v); err != nil {
			return "", mcp.BadArgs("%s must be a day like 2026-11-24", name)
		}
		return v, nil
	}
	from, err := day("from", a.From)
	if err != nil {
		return nil, err
	}
	to, err := day("to", a.To)
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	if _, err := export.Workload(ctx, s.Store, ws, time.FixedZone("", offset*60), &buf); err != nil {
		return nil, err
	}
	rows, err := csv.NewReader(strings.NewReader(strings.TrimPrefix(buf.String(), "\ufeff"))).ReadAll()
	if err != nil || len(rows) == 0 {
		return nil, fmt.Errorf("workload table: %v", err)
	}
	head := rows[0]
	filtered := from != "" || to != ""
	out := []map[string]string{}
	for _, row := range rows[1:] {
		if len(row) != len(head) {
			continue
		}
		d := row[0]
		if d == "合计" && filtered || d != "合计" && (from != "" && d < from || to != "" && d > to) {
			continue
		}
		m := make(map[string]string, len(head))
		for i, h := range head {
			m[h] = row[i]
		}
		out = append(out, m)
	}
	return map[string]any{"columns": head, "rows": out}, nil
}

func (s *Server) mcpReferenceAt(ctx context.Context, ws string, raw json.RawMessage) (any, error) {
	a, err := args[struct {
		Record string   `json:"record"`
		Lon    *float64 `json:"lon"`
		Lat    *float64 `json:"lat"`
		Reach  float64  `json:"reach_m"`
	}](raw)
	if err != nil {
		return nil, err
	}
	if a.Record != "" {
		rec, err := export.LiveRecord(ctx, s.Store, ws, a.Record)
		if errors.Is(err, store.ErrNotFound) {
			return nil, mcp.BadArgs("no record %q here", a.Record)
		}
		if err != nil {
			return nil, err
		}
		if rec.Lon == nil {
			return nil, mcp.BadArgs("record %q has no position", a.Record)
		}
		a.Lon, a.Lat = rec.Lon, rec.Lat
	}
	if a.Lon == nil || a.Lat == nil {
		return nil, mcp.BadArgs("give record, or both lon and lat")
	}
	reach := a.Reach
	if reach <= 0 {
		reach = 200
	}
	if reach > 5000 {
		return nil, mcp.BadArgs("reach_m is at most 5000")
	}
	settings, _, err := s.Store.Settings(ctx, ws)
	if err != nil {
		return nil, err
	}
	layers := export.ReferenceLayers(settings)
	areas, lines := []export.ReferenceHit{}, []export.ReferenceHit{}
	var unread []string
	for _, l := range layers {
		if s.Files == nil {
			unread = append(unread, l.Name)
			continue
		}
		f, err := s.Files.Open(ws, l.File)
		if err != nil {
			unread = append(unread, l.Name)
			continue
		}
		as, ls, err := export.ReferenceAt(l, f, *a.Lon, *a.Lat, reach)
		f.Close()
		if err != nil {
			s.logger().Warn("mcp reference layer", "layer", l.ID, "err", err)
			unread = append(unread, l.Name)
			continue
		}
		areas = append(areas, as...)
		lines = append(lines, ls...)
	}
	sort.SliceStable(lines, func(i, j int) bool { return *lines[i].DistanceM < *lines[j].DistanceM })
	out := map[string]any{"lon": *a.Lon, "lat": *a.Lat, "layers": len(layers), "areas": areas, "lines": lines}
	if len(layers) == 0 {
		out["note"] = "the workspace shares no reference layers; a leader shares them from the app's layer list"
	}
	if len(unread) > 0 {
		out["unreadable_layers"] = unread
	}
	return out, nil
}

type mcpPosition struct {
	Nickname   string    `json:"nickname"`
	Online     bool      `json:"online"`
	Lon        float64   `json:"lon"`
	Lat        float64   `json:"lat"`
	AccuracyM  float64   `json:"accuracy_m,omitempty"`
	At         time.Time `json:"at"`
	AgeSeconds int64     `json:"age_seconds"`
}

func (s *Server) mcpPositions(ctx context.Context, ws string) (any, error) {
	ms, err := s.Store.Members(ctx, ws)
	if err != nil {
		return nil, err
	}
	names := map[string]string{}
	for _, m := range ms {
		if !m.Revoked {
			names[m.ID] = m.Nickname
		}
	}
	ps, online := s.Hub.Positions(ws)
	now := time.Now()
	out := []mcpPosition{}
	placed := map[string]bool{}
	for _, p := range ps {
		name, ok := names[p.MemberID]
		if !ok {
			continue // revoked since
		}
		placed[p.MemberID] = true
		out = append(out, mcpPosition{
			Nickname: name, Online: online[p.MemberID], Lon: p.Lon, Lat: p.Lat, AccuracyM: p.Accuracy,
			At: p.At, AgeSeconds: max(0, int64(now.Sub(p.At).Seconds())),
		})
	}
	without := []string{}
	for id := range online {
		if name, ok := names[id]; ok && !placed[id] {
			without = append(without, name)
		}
	}
	sort.Strings(without)
	return map[string]any{"positions": out, "online_without_position": without}, nil
}

type mcpTask struct {
	ID       string   `json:"id"`
	Kind     string   `json:"kind"`
	Title    string   `json:"title,omitempty"`
	Assignee string   `json:"assignee"`
	Status   string   `json:"status"`
	Lon      *float64 `json:"lon,omitempty"`
	Lat      *float64 `json:"lat,omitempty"`
	// UpdatedAt is of the record's latest change, which for a finished
	// task is usually when it was marked done.
	UpdatedAt time.Time `json:"updated_at"`
}

var taskOrder = map[string]int{"todo": 0, "doing": 1, "done": 2}

func (s *Server) mcpTasks(ctx context.Context, ws string, raw json.RawMessage) (any, error) {
	a, err := args[struct {
		Member string `json:"member"`
		Status string `json:"status"`
		Limit  int    `json:"limit"`
	}](raw)
	if err != nil {
		return nil, err
	}
	if _, ok := taskOrder[a.Status]; a.Status != "" && !ok {
		return nil, mcp.BadArgs("status must be todo, doing or done")
	}
	ms, err := s.Store.Members(ctx, ws)
	if err != nil {
		return nil, err
	}
	names := map[string]string{}
	for _, m := range ms {
		names[m.ID] = m.Nickname
	}
	type counts struct {
		Todo  int `json:"todo"`
		Doing int `json:"doing"`
		Done  int `json:"done"`
	}
	per := map[string]*counts{}
	rows := []mcpTask{}
	err = export.EachRecord(ctx, s.Store, ws, func(rec export.Record) error {
		var p struct {
			Assignee     string `json:"assignee"`
			AssigneeName string `json:"assignee_name"`
			Status       string `json:"task_status"`
		}
		if json.Unmarshal(rec.Feature.Properties, &p) != nil || p.Assignee == "" {
			return nil
		}
		who := names[p.Assignee]
		if who == "" {
			who = p.AssigneeName
		}
		if who == "" {
			who = p.Assignee
		}
		if _, ok := taskOrder[p.Status]; !ok {
			p.Status = "todo" // §6.1: unknown values show as todo
		}
		c := per[who]
		if c == nil {
			c = &counts{}
			per[who] = c
		}
		switch p.Status {
		case "todo":
			c.Todo++
		case "doing":
			c.Doing++
		default:
			c.Done++
		}
		if (a.Member != "" && !strings.EqualFold(who, strings.TrimSpace(a.Member))) || (a.Status != "" && p.Status != a.Status) {
			return nil
		}
		rows = append(rows, mcpTask{rec.ID, rec.Kind, rec.Title, who, p.Status, rec.Lon, rec.Lat, rec.UpdatedAt})
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.SliceStable(rows, func(i, j int) bool {
		if rows[i].Status != rows[j].Status {
			return taskOrder[rows[i].Status] < taskOrder[rows[j].Status]
		}
		if rows[i].Assignee != rows[j].Assignee {
			return rows[i].Assignee < rows[j].Assignee
		}
		return rows[i].Title < rows[j].Title
	})
	matched := len(rows)
	if limit := clampLimit(a.Limit, 50, 200); matched > limit {
		rows = rows[:limit]
	}
	return map[string]any{"matched": matched, "tasks": rows, "by_member": per}, nil
}

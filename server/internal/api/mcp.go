package api

import (
	"context"
	"encoding/json"
	"errors"
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
// §11), with the calling member's token. Writing stays in the app, where
// actions carry risk classes and confirmations (actions.md).
func (s *Server) mcpEndpoint(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	srv := &mcp.Server{Name: "geocrew", Version: s.Version, Instructions: mcpInstructions, Tools: s.mcpTools(ws),
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
			Description: "Find records by words in title, lithology, note or sample numbers, by kind, by who changed them last and since when. Newest first.",
			InputSchema: schema(`"query":{"type":"string","description":"words that must all appear (title, lithology, note, sample no.)"},` +
				`"kind":{"type":"string","description":"e.g. geo_point, track, line, polygon, section, route, borehole"},` +
				`"member":{"type":"string","description":"nickname of who changed it last"},` +
				`"since":{"type":"string","description":"date or time, e.g. 2026-11-24 or RFC 3339"},` +
				`"limit":{"type":"integer","minimum":1,"maximum":100,"default":20}`),
			Call: func(ctx context.Context, args json.RawMessage) (any, error) { return s.mcpSearch(ctx, ws, args) },
		},
		{
			Name: "get_record", Title: "查看记录",
			Description: "One record in full: geometry, all properties and sync metadata, plus who changed it last and how many earlier versions its history keeps.",
			InputSchema: schema(`"id":{"type":"string"}`, "id"),
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
			Name: "recent_messages", Title: "组内消息",
			Description: "The latest workspace messages and record comments, newest last, including calls for help and roll calls.",
			InputSchema: schema(`"limit":{"type":"integer","minimum":1,"maximum":100,"default":30}`),
			Call:        func(ctx context.Context, args json.RawMessage) (any, error) { return s.mcpMessages(ctx, ws, args) },
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
		Query  string `json:"query"`
		Kind   string `json:"kind"`
		Member string `json:"member"`
		Since  string `json:"since"`
		Limit  int    `json:"limit"`
	}](raw)
	if err != nil {
		return nil, err
	}
	since, err := parseSince(a.Since)
	if err != nil {
		return nil, err
	}
	words := strings.Fields(strings.ToLower(a.Query))
	who := strings.TrimSpace(a.Member)
	var rows []mcpRecordRow
	err = export.EachRecord(ctx, s.Store, ws, func(rec export.Record) error {
		if a.Kind != "" && rec.Kind != a.Kind || who != "" && rec.UpdatedBy != who ||
			!since.IsZero() && rec.UpdatedAt.Before(since) {
			return nil
		}
		text := strings.ToLower(rec.Title + " " + rec.Lithology + " " + rec.Note + " " + strings.Join(rec.Samples, " "))
		for _, w := range words {
			if !strings.Contains(text, w) {
				return nil
			}
		}
		rows = append(rows, mcpRecordRow{
			ID: rec.ID, Kind: rec.Kind, Title: rec.Title, Lithology: rec.Lithology, Note: clip(rec.Note, 160),
			Samples: rec.Samples, Lon: rec.Lon, Lat: rec.Lat, UpdatedAt: rec.UpdatedAt, UpdatedBy: rec.UpdatedBy,
		})
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.SliceStable(rows, func(i, j int) bool { return rows[i].UpdatedAt.After(rows[j].UpdatedAt) })
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
		return nil, mcp.BadArgs("no record %q here; search_records lists the ids", a.ID)
	}
	if err != nil {
		return nil, err
	}
	hist, err := s.Store.FeatureHistory(ctx, ws, a.ID)
	if err != nil {
		return nil, err
	}
	return map[string]any{"feature": toWire(rec.Feature), "updated_by": rec.UpdatedBy, "earlier_versions": len(hist)}, nil
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
	var all []mcpMessage
	var since int64
	for {
		cs, more, err := s.Store.Comments(ctx, ws, since, 500)
		if err != nil {
			return nil, err
		}
		for _, c := range cs {
			since = c.Seq
			if c.Deleted {
				continue
			}
			all = append(all, mcpMessage{c.Nickname, c.Body, c.CreatedAt, c.FeatureID, c.Urgent, c.RollCall})
		}
		if !more || len(cs) == 0 {
			break
		}
	}
	if len(all) > limit {
		all = all[len(all)-limit:]
	}
	if all == nil {
		all = []mcpMessage{}
	}
	return map[string]any{"messages": all}, nil
}

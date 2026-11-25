package api

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"slices"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/export"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/mcp"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
)

// mcpWriteTools are offered only with an AI link (protocol v0 §11.1). They
// act as the member who made the link, under the member's current role,
// the leader's ai_limits and the workspace's archive state.
func (s *Server) mcpWriteTools(ws string, m store.Member) []mcp.Tool {
	return []mcp.Tool{
		{
			Name: "add_comment", Title: "留言", Effect: mcp.Destructive,
			Description: "Post a message to the whole team, or a comment on one record (by id or name such as D012). Everyone in the workspace sees it and may get a notification, so confirm the text with the user first.",
			InputSchema: schema(`"body":{"type":"string","minLength":1,"maxLength":2000},"record":{"type":"string","description":"record id or name; omit for a workspace message"}`, "body"),
			Call:        func(ctx context.Context, args json.RawMessage) (any, error) { return s.mcpAddComment(ctx, ws, m, args) },
		},
		{
			Name: "set_task_status", Title: "改任务状态", Effect: mcp.Write,
			Description: "Set the status of a record assigned to a member (see the tasks tool): todo, doing or done. The change is synced to everyone and kept in the record's history.",
			InputSchema: schema(`"record":{"type":"string","description":"record id or name"},"status":{"type":"string","enum":["todo","doing","done"]}`, "record", "status"),
			Call: func(ctx context.Context, args json.RawMessage) (any, error) {
				return s.mcpSetTaskStatus(ctx, ws, m, args)
			},
		},
	}
}

// mcpMayWrite refuses a write of the given ai_limits category (actions.md §3).
func (s *Server) mcpMayWrite(ctx context.Context, ws string, m store.Member, category string) error {
	if !m.Role.CanWrite() {
		return mcp.BadArgs("your role in this workspace is read-only")
	}
	w, err := s.Store.Workspace(ctx, ws)
	if err != nil {
		return err
	}
	if w.ArchivedAt != nil {
		return mcp.BadArgs("the workspace is archived; nothing can be changed until the leader reopens it")
	}
	raw, _, err := s.Store.Settings(ctx, ws)
	if err != nil {
		return err
	}
	var st struct {
		AILimits struct {
			Deny []string `json:"deny"`
		} `json:"ai_limits"`
	}
	_ = json.Unmarshal(raw, &st)
	if slices.Contains(st.AILimits.Deny, category) {
		return mcp.BadArgs("the leader does not allow AI tools to do this (%s) in this workspace", category)
	}
	return nil
}

// mcpFind returns the live record with this id, or else the one record with this name.
func (s *Server) mcpFind(ctx context.Context, ws, key string) (export.Record, error) {
	key = strings.TrimSpace(key)
	rec, err := export.LiveRecord(ctx, s.Store, ws, key)
	if !errors.Is(err, store.ErrNotFound) {
		return rec, err
	}
	var hits []export.Record
	if err := export.EachRecord(ctx, s.Store, ws, func(r export.Record) error {
		if r.Title != "" && strings.EqualFold(r.Title, key) {
			hits = append(hits, r)
		}
		return nil
	}); err != nil {
		return rec, err
	}
	switch len(hits) {
	case 1:
		return hits[0], nil
	case 0:
		return rec, mcp.BadArgs("no record %q here; search_records lists the ids", key)
	default:
		return rec, mcp.BadArgs("%d records are named %q; use search_records and pass an id", len(hits), key)
	}
}

func (s *Server) mcpAddComment(ctx context.Context, ws string, m store.Member, raw json.RawMessage) (any, error) {
	a, err := args[struct {
		Body   string `json:"body"`
		Record string `json:"record"`
	}](raw)
	if err != nil {
		return nil, err
	}
	if err := s.mcpMayWrite(ctx, ws, m, "send"); err != nil {
		return nil, err
	}
	req := addCommentReq{ID: "ai-" + randID(), Body: a.Body}
	if a.Record != "" {
		rec, err := s.mcpFind(ctx, ws, a.Record)
		if err != nil {
			return nil, err
		}
		req.FeatureID = rec.ID
	}
	c, err := req.validate()
	if err != nil {
		return nil, mcp.BadArgs("%v", err)
	}
	out, err := s.Store.AddComment(ctx, ws, m.ID, c)
	if err != nil {
		return nil, err
	}
	s.Hub.CommentsChanged(ws, out.Seq)
	s.notifyComment(ws, out)
	return map[string]any{"posted": true, "id": out.ID, "record": out.FeatureID, "at": out.CreatedAt}, nil
}

func (s *Server) mcpSetTaskStatus(ctx context.Context, ws string, m store.Member, raw json.RawMessage) (any, error) {
	a, err := args[struct {
		Record string `json:"record"`
		Status string `json:"status"`
	}](raw)
	if err != nil {
		return nil, err
	}
	if _, ok := taskOrder[a.Status]; !ok {
		return nil, mcp.BadArgs("status must be todo, doing or done")
	}
	if err := s.mcpMayWrite(ctx, ws, m, "edit"); err != nil {
		return nil, err
	}
	rec, err := s.mcpFind(ctx, ws, a.Record)
	if err != nil {
		return nil, err
	}
	var props map[string]any
	if err := json.Unmarshal(rec.Feature.Properties, &props); err != nil || props == nil {
		return nil, mcp.BadArgs("record %s has no properties to change", rec.ID)
	}
	if who, _ := props["assignee"].(string); who == "" {
		return nil, mcp.BadArgs("record %s is not assigned to anyone", rec.ID)
	}
	if props["task_status"] == a.Status {
		return map[string]any{"changed": false, "id": rec.ID, "status": a.Status, "version": rec.Feature.Version}, nil
	}
	props["task_status"] = a.Status
	b, _ := json.Marshal(props)
	f := rec.Feature
	f.Properties = b
	f.Version++
	f.UpdatedAt = time.Now().UTC()
	results, seq, err := s.Store.PushFeatures(ctx, ws, m.ID, []store.Feature{f}, store.LockProps(ReviewProp))
	if err != nil {
		return nil, err
	}
	if len(results) != 1 || results[0].Status != store.PushApplied {
		return nil, mcp.BadArgs("someone changed %s at the same moment; read it again and retry", rec.ID)
	}
	s.Hub.FeatureChanged(ws, seq)
	return map[string]any{"changed": true, "id": rec.ID, "status": a.Status, "version": f.Version}, nil
}

func randID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

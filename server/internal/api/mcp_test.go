package api

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/realtime"
)

type rpcResp struct {
	ID     json.RawMessage `json:"id"`
	Result struct {
		ProtocolVersion   string                  `json:"protocolVersion"`
		Tools             []json.RawMessage       `json:"tools"`
		IsError           bool                    `json:"isError"`
		Content           []struct{ Text string } `json:"content"`
		StructuredContent json.RawMessage         `json:"structuredContent"`
	} `json:"result"`
	Error *struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

func TestMCP(t *testing.T) {
	var hub *realtime.Hub
	e := newEnvWith(t, func(s *Server) { hub = s.Hub })
	var a joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "野外一组", "nickname": "组长"}, &a)
	base := "/api/v0/workspaces/" + a.WorkspaceID
	f1 := feature("f1", 1, "2026-11-24T08:00:00Z")
	f1["properties"] = map[string]any{"kind": "geo_point", "title": "D001", "lithology": "花岗岩", "note": "灰白色中粒", "samples": []any{map[string]any{"no": "S-12"}}}
	f2 := feature("f2", 1, "2026-11-25T08:00:00Z")
	f2["properties"] = map[string]any{"kind": "geo_point", "title": "D002", "lithology": "片麻岩", "assignee": a.MemberID, "assignee_name": "组长", "task_status": "doing"}
	e.call("POST", base+"/features", a.Token, map[string]any{"features": []any{f1, f2}}, nil)
	e.call("POST", base+"/comments", a.Token, map[string]any{"id": "c1", "body": "下午三点集合"}, nil)
	e.call("POST", base+"/comments", a.Token, map[string]any{"id": "c2", "feature_id": "f1", "body": "接触关系再看一下"}, nil)

	rpc := func(token string, id int, method string, params any) (int, rpcResp) {
		t.Helper()
		var out rpcResp
		c := e.call("POST", base+"/mcp", token, map[string]any{"jsonrpc": "2.0", "id": id, "method": method, "params": params}, &out)
		return c, out
	}
	tool := func(name string, args any) (map[string]any, rpcResp) {
		t.Helper()
		c, r := rpc(a.Token, 9, "tools/call", map[string]any{"name": name, "arguments": args})
		if c != 200 || r.Error != nil {
			t.Fatalf("%s: %d %+v", name, c, r.Error)
		}
		var v map[string]any
		_ = json.Unmarshal(r.Result.StructuredContent, &v)
		return v, r
	}

	if c := e.call("POST", base+"/mcp", "", map[string]any{"jsonrpc": "2.0", "id": 1, "method": "initialize"}, nil); c != 401 {
		t.Fatalf("no token: %d", c)
	}
	if c, r := rpc(a.Token, 1, "initialize", map[string]any{"protocolVersion": "2025-03-26"}); c != 200 || r.Result.ProtocolVersion != "2025-03-26" || string(r.ID) != "1" {
		t.Fatalf("initialize: %d %+v", c, r)
	}
	if c, r := rpc(a.Token, 1, "initialize", map[string]any{"protocolVersion": "1999-01-01"}); c != 200 || r.Result.ProtocolVersion != "2025-06-18" {
		t.Fatalf("initialize unknown version: %d %+v", c, r)
	}
	// Notifications get 202 and no body.
	if c := e.call("POST", base+"/mcp", a.Token, map[string]any{"jsonrpc": "2.0", "method": "notifications/initialized"}, nil); c != 202 {
		t.Fatalf("notification: %d", c)
	}
	if c := e.call("POST", base+"/mcp", a.Token, []byte(`[{"jsonrpc":"2.0","id":1,"method":"ping"}]`), nil); c != 400 {
		t.Fatalf("batch: %d", c)
	}
	if _, r := rpc(a.Token, 2, "resources/list", nil); r.Error == nil || r.Error.Code != -32601 {
		t.Fatalf("unknown method: %+v", r)
	}
	if _, r := rpc(a.Token, 3, "tools/list", nil); len(r.Result.Tools) != 9 {
		t.Fatalf("tools/list: %d tools", len(r.Result.Tools))
	}

	if pos, _ := tool("team_positions", nil); len(pos["positions"].([]any)) != 0 || len(pos["online_without_position"].([]any)) != 0 {
		t.Fatalf("positions before anyone shares: %v", pos)
	}
	me := &realtime.Client{MemberID: a.MemberID, Nickname: "组长", Send: make(chan []byte, 8)}
	hub.Join(a.WorkspaceID, me)
	if pos, _ := tool("team_positions", nil); fmt.Sprint(pos["online_without_position"]) != "[组长]" {
		t.Fatalf("online without position: %v", pos)
	}
	hub.UpdatePosition(a.WorkspaceID, me, realtime.Position{Lon: 116.3, Lat: 39.9, Accuracy: 6})
	pos, _ := tool("team_positions", nil)
	ps := pos["positions"].([]any)
	if len(ps) != 1 || ps[0].(map[string]any)["nickname"] != "组长" || ps[0].(map[string]any)["online"] != true || ps[0].(map[string]any)["lat"] != 39.9 {
		t.Fatalf("positions: %v", pos)
	}
	hub.Leave(a.WorkspaceID, me)
	if pos, _ := tool("team_positions", nil); pos["positions"].([]any)[0].(map[string]any)["online"] != false {
		t.Fatalf("position after leaving: %v", pos)
	}

	if ts, _ := tool("tasks", map[string]any{"status": "doing"}); ts["matched"] != 1.0 || !strings.Contains(string(mustJSON(ts)), `"D002"`) {
		t.Fatalf("tasks: %v", ts)
	}
	sum, _ := tool("workspace_summary", nil)
	if sum["name"] != "野外一组" || sum["records"] != 2.0 || sum["samples"] != 1.0 {
		t.Fatalf("summary: %v", sum)
	}
	found, _ := tool("search_records", map[string]any{"query": "花岗岩 s-12"})
	if found["matched"] != 1.0 || !strings.Contains(string(mustJSON(found)), `"D001"`) {
		t.Fatalf("search: %v", found)
	}
	newest, _ := tool("search_records", map[string]any{"kind": "geo_point", "since": "2026-11-25", "member": "组长"})
	if newest["matched"] != 1.0 || !strings.Contains(string(mustJSON(newest)), `"D002"`) {
		t.Fatalf("search since: %v", newest)
	}
	if _, r := tool("search_records", map[string]any{"since": "昨天"}); !r.Result.IsError || !strings.Contains(r.Result.Content[0].Text, "since") {
		t.Fatalf("bad since: %+v", r.Result)
	}
	f3 := feature("f3", 1, "2026-11-25T09:00:00Z")
	f3["geometry"] = map[string]any{"type": "Point", "coordinates": []float64{116.40, 39.91}} // about 850 m east
	f3["properties"] = map[string]any{"kind": "geo_point", "title": "D003", "lithology": "花岗岩"}
	e.call("POST", base+"/features", a.Token, map[string]any{"features": []any{f3}}, nil)
	around, _ := tool("search_records", map[string]any{"near_record": "f1", "radius_m": 2000})
	if around["matched"] != 2.0 || !strings.Contains(string(mustJSON(around)), `"D003"`) || !strings.Contains(string(mustJSON(around)), `"distance_m":85`) {
		t.Fatalf("near: %v", around)
	}
	if far, _ := tool("search_records", map[string]any{"near_lon": 116.39, "near_lat": 39.91, "radius_m": 100}); far["matched"] != 2.0 {
		t.Fatalf("near point: %v", far)
	}
	if _, r := tool("search_records", map[string]any{"near_lon": 116.39}); !r.Result.IsError {
		t.Fatal("near_lon alone must be refused")
	}
	if ref, _ := tool("reference_at", map[string]any{"record": "f1"}); ref["layers"] != 0.0 || ref["note"] == nil {
		t.Fatalf("reference_at without layers: %v", ref)
	}
	gj := []byte(`{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"unit":"K1b"},"geometry":{"type":"Polygon","coordinates":[[[116,39],[117,39],[117,40],[116,40],[116,39]]]}}]}`)
	sum256 := sha256.Sum256(gj)
	sha := hex.EncodeToString(sum256[:])
	if c := e.call("PUT", base+"/files/"+sha, a.Token, gj, nil); c != 201 && c != 200 {
		t.Fatalf("upload layer: %d", c)
	}
	var cur struct {
		Version int64 `json:"version"`
	}
	e.call("GET", base+"/settings", a.Token, nil, &cur)
	if c := e.call("PUT", base+"/settings", a.Token, map[string]any{"base_version": cur.Version, "settings": map[string]any{
		"reference_layers": []any{map[string]any{"id": "r1", "name": "1:5万地质图", "file": sha, "color_by": "unit"}},
	}}, nil); c != 200 {
		t.Fatalf("settings: %d", c)
	}
	if ref, _ := tool("reference_at", map[string]any{"lon": 116.39, "lat": 39.91}); !strings.Contains(string(mustJSON(ref)), `"value":"K1b"`) {
		t.Fatalf("reference_at: %v", ref)
	}
	rec, _ := tool("get_record", map[string]any{"id": "f1"})
	if !strings.Contains(string(mustJSON(rec)), "灰白色中粒") || rec["updated_by"] != "组长" {
		t.Fatalf("get_record: %v", rec)
	}
	if cs := rec["comments"].([]any); len(cs) != 1 || cs[0].(map[string]any)["body"] != "接触关系再看一下" {
		t.Fatalf("get_record comments: %v", rec["comments"])
	}
	if byName, _ := tool("get_record", map[string]any{"id": "d002"}); byName["feature"].(map[string]any)["id"] != "f2" {
		t.Fatalf("get_record by name: %v", byName)
	}
	if _, r := tool("get_record", map[string]any{"id": "nope"}); !r.Result.IsError {
		t.Fatalf("missing record: %+v", r.Result)
	}
	msgs, _ := tool("recent_messages", nil)
	if !strings.Contains(string(mustJSON(msgs)), "下午三点集合") {
		t.Fatalf("messages: %v", msgs)
	}
	work, _ := tool("workload", map[string]any{"from": "2026-11-25", "to": "2026-11-25"})
	if rows, _ := work["rows"].([]any); len(rows) != 1 || !strings.Contains(string(mustJSON(rows)), `"组长"`) {
		t.Fatalf("workload: %v", work)
	}
	if _, r := tool("workload", map[string]any{"from": "上周"}); !r.Result.IsError {
		t.Fatalf("bad from: %+v", r.Result)
	}
	if _, r := tool("review_issues", nil); r.Result.IsError {
		t.Fatalf("review_issues: %+v", r.Result)
	}
	if _, r := rpc(a.Token, 4, "tools/call", map[string]any{"name": "delete_everything"}); r.Error == nil {
		t.Fatal("unknown tool must be an error")
	}
	// An AI link adds the write tools and works nowhere but /mcp.
	if c := e.call("POST", base+"/ai-links", a.Token, map[string]int{"hours": 25}, nil); c != 400 {
		t.Fatalf("ai link of 25 hours: %d", c)
	}
	var ai viewLinkResp
	if c := e.call("POST", base+"/ai-links", a.Token, nil, &ai); c != 201 || !strings.HasPrefix(ai.Token, "a_") {
		t.Fatalf("ai link: %d %+v", c, ai)
	}
	if c := e.call("GET", base+"/features?since=0", ai.Token, nil, nil); c != 403 {
		t.Fatalf("ai link outside mcp: %d", c)
	}
	if c := e.call("POST", base+"/ai-links", ai.Token, nil, nil); c != 403 {
		t.Fatalf("ai link making another: %d", c)
	}
	aiTool := func(name string, args any) (map[string]any, rpcResp) {
		t.Helper()
		c, r := rpc(ai.Token, 9, "tools/call", map[string]any{"name": name, "arguments": args})
		if c != 200 || r.Error != nil {
			t.Fatalf("%s: %d %+v", name, c, r.Error)
		}
		var v map[string]any
		_ = json.Unmarshal(r.Result.StructuredContent, &v)
		return v, r
	}
	if _, r := rpc(ai.Token, 3, "tools/list", nil); len(r.Result.Tools) != 11 || !strings.Contains(string(r.Result.Tools[10]), `"readOnlyHint":false`) {
		t.Fatalf("ai tools/list: %d", len(r.Result.Tools))
	}
	if v, _ := aiTool("add_comment", map[string]any{"record": "D001", "body": "AI 帮我记一下：接触带待复查"}); v["posted"] != true || v["record"] != "f1" {
		t.Fatalf("add_comment: %v", v)
	}
	if v, _ := aiTool("set_task_status", map[string]any{"record": "D002", "status": "done"}); v["changed"] != true || v["version"] != 2.0 {
		t.Fatalf("set_task_status: %v", v)
	}
	if ts, _ := tool("tasks", map[string]any{"status": "done"}); ts["matched"] != 1.0 {
		t.Fatalf("task not done: %v", ts)
	}
	if _, r := aiTool("set_task_status", map[string]any{"record": "D001", "status": "done"}); !r.Result.IsError {
		t.Fatal("unassigned record must be refused")
	}
	e.call("PUT", base+"/settings", a.Token, map[string]any{"settings": map[string]any{"ai_limits": map[string]any{"deny": []string{"send"}}}}, nil)
	if _, r := aiTool("add_comment", map[string]any{"body": "x"}); !r.Result.IsError || !strings.Contains(r.Result.Content[0].Text, "leader") {
		t.Fatalf("ai_limits deny send: %+v", r.Result)
	}
	e.call("DELETE", base+"/view-links", a.Token, nil, nil)
	if c := e.call("POST", base+"/mcp", ai.Token, map[string]any{"jsonrpc": "2.0", "id": 6, "method": "tools/list"}, nil); c != 401 {
		t.Fatalf("revoked ai link: %d", c)
	}

	// A view link is the read-only credential to give an AI tool.
	var link viewLinkResp
	e.call("POST", base+"/view-links", a.Token, nil, &link)
	if c, r := rpc(link.Token, 5, "tools/list", nil); c != 200 || len(r.Result.Tools) != 9 {
		t.Fatalf("view link: %d", c)
	}
	e.call("DELETE", base+"/view-links", a.Token, nil, nil)
	if c := e.call("POST", base+"/mcp", link.Token, map[string]any{"jsonrpc": "2.0", "id": 6, "method": "tools/list"}, nil); c != 401 {
		t.Fatalf("revoked view link: %d", c)
	}
}

func mustJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

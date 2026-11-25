package api

import (
	"encoding/json"
	"strings"
	"testing"
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
	e := newEnv(t)
	var a joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "野外一组", "nickname": "组长"}, &a)
	base := "/api/v0/workspaces/" + a.WorkspaceID
	f1 := feature("f1", 1, "2026-11-24T08:00:00Z")
	f1["properties"] = map[string]any{"kind": "geo_point", "title": "D001", "lithology": "花岗岩", "note": "灰白色中粒", "samples": []any{map[string]any{"no": "S-12"}}}
	f2 := feature("f2", 1, "2026-11-25T08:00:00Z")
	f2["properties"] = map[string]any{"kind": "geo_point", "title": "D002", "lithology": "片麻岩"}
	e.call("POST", base+"/features", a.Token, map[string]any{"features": []any{f1, f2}}, nil)
	e.call("POST", base+"/comments", a.Token, map[string]any{"id": "c1", "body": "下午三点集合"}, nil)

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
	if _, r := rpc(a.Token, 3, "tools/list", nil); len(r.Result.Tools) != 6 {
		t.Fatalf("tools/list: %d tools", len(r.Result.Tools))
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
	rec, _ := tool("get_record", map[string]any{"id": "f1"})
	if !strings.Contains(string(mustJSON(rec)), "灰白色中粒") || rec["updated_by"] != "组长" {
		t.Fatalf("get_record: %v", rec)
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
	// A view link is the read-only credential to give an AI tool.
	var link viewLinkResp
	e.call("POST", base+"/view-links", a.Token, nil, &link)
	if c, r := rpc(link.Token, 5, "tools/list", nil); c != 200 || len(r.Result.Tools) != 6 {
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

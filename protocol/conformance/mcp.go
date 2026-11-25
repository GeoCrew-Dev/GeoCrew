package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

// mcpChecks cover the read-only MCP endpoint (§11) as an AI client uses it.
func (s *suite) mcpChecks() []check {
	rpc := func(token string, msg map[string]any) (response, map[string]any, error) {
		r, err := s.c.do(http.MethodPost, wsPath(s.ws, "/mcp"), token, msg, nil)
		if err != nil {
			return r, nil, err
		}
		var out map[string]any
		if r.status == http.StatusOK {
			if err := json.Unmarshal(r.body, &out); err != nil {
				return r, nil, fmt.Errorf("响应不是 JSON：%s", clip(r.body))
			}
		}
		return r, out, nil
	}
	return []check{
		{section: "§11", name: "MCP：初始化、通知 202、列出工具、按名称查到刚提交的记录", needs: "mcp", workspace: true, run: func() error {
			r, out, err := rpc(s.member, map[string]any{"jsonrpc": "2.0", "id": 1, "method": "initialize",
				"params": map[string]any{"protocolVersion": "2025-06-18", "capabilities": map[string]any{}, "clientInfo": map[string]any{"name": "conformance", "version": "0"}}})
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			res, _ := out["result"].(map[string]any)
			if res == nil || res["protocolVersion"] != "2025-06-18" {
				return fmt.Errorf("initialize 应返回 protocolVersion 2025-06-18：%s", clip(r.body))
			}
			if r, _, err = rpc(s.member, map[string]any{"jsonrpc": "2.0", "method": "notifications/initialized"}); err != nil {
				return err
			} else if err := expect(r, http.StatusAccepted, ""); err != nil {
				return fmt.Errorf("通知：%v", err)
			}
			r, out, err = rpc(s.member, map[string]any{"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
			if err != nil {
				return err
			}
			for _, want := range []string{"workspace_summary", "search_records", "get_record", "review_issues", "recent_messages"} {
				if !strings.Contains(string(r.body), `"`+want+`"`) {
					return fmt.Errorf("tools/list 里没有 %s", want)
				}
			}
			title := "一致性MCP" + randomID()[:6]
			id := randomID()
			if _, _, err := s.push(s.leader, feature(id, 1, nowish(), map[string]any{"kind": "geo_point", "title": title, "lithology": "花岗岩"})); err != nil {
				return err
			}
			r, out, err = rpc(s.member, map[string]any{"jsonrpc": "2.0", "id": 3, "method": "tools/call",
				"params": map[string]any{"name": "search_records", "arguments": map[string]any{"query": title}}})
			if err != nil {
				return err
			}
			res, _ = out["result"].(map[string]any)
			if res == nil || res["isError"] == true || !strings.Contains(string(r.body), id) {
				return fmt.Errorf("search_records 没有找到刚提交的记录：%s", clip(r.body))
			}
			return nil
		}},
		{section: "§11", name: "MCP：查看链接不能用，返回 403", needs: "mcp", workspace: true, run: func() error {
			r, err := s.c.do(http.MethodPost, wsPath(s.ws, "/view-links"), s.member, map[string]any{"hours": 1}, nil)
			if err != nil {
				return err
			}
			var link struct {
				Token string `json:"token"`
			}
			if err := r.json(&link); err != nil || link.Token == "" {
				return fmt.Errorf("生成查看链接失败：%s", clip(r.body))
			}
			r, _, err = rpc(link.Token, map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
			if err != nil {
				return err
			}
			return expect(r, http.StatusForbidden, "")
		}},
	}
}

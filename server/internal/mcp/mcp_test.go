package mcp

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func TestHandle(t *testing.T) {
	var logged []string
	s := &Server{Name: "t", Version: "1", Log: func(tool string, err error) { logged = append(logged, tool+": "+err.Error()) },
		Tools: []Tool{
			{Name: "ok", InputSchema: json.RawMessage(`{"type":"object"}`), Call: func(context.Context, json.RawMessage) (any, error) {
				return map[string]int{"n": 1}, nil
			}},
			{Name: "bad", Call: func(context.Context, json.RawMessage) (any, error) { return nil, BadArgs("limit too big") }},
			{Name: "boom", Call: func(context.Context, json.RawMessage) (any, error) { return nil, errors.New("disk at /var/x failed") }},
		}}
	call := func(body string) (int, string) {
		status, resp := s.Handle(context.Background(), []byte(body))
		b, _ := json.Marshal(resp)
		return status, string(b)
	}
	cases := []struct {
		body, want string
		status     int
	}{
		{`{`, `"code":-32700`, 400},
		{`[]`, `batches are not supported`, 400},
		{`{"id":1,"method":"ping"}`, `"code":-32600`, 400},
		{`{"jsonrpc":"2.0","id":"a","method":"ping"}`, `{"jsonrpc":"2.0","id":"a","result":{}}`, 200},
		{`{"jsonrpc":"2.0","method":"notifications/initialized"}`, `null`, 202},
		{`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ok"}}`, `"structuredContent":{"n":1}`, 200},
		{`{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"bad"}}`, `limit too big`, 200},
		{`{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"boom"}}`, `try again later`, 200},
		{`{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{}}`, `"code":-32602`, 200},
	}
	for _, c := range cases {
		status, got := call(c.body)
		if status != c.status || !strings.Contains(got, c.want) {
			t.Errorf("%s: %d %s, want %d with %s", c.body, status, got, c.status, c.want)
		}
	}
	if _, got := call(`{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"boom"}}`); strings.Contains(got, "/var/x") {
		t.Errorf("internal error leaked: %s", got)
	}
	if len(logged) == 0 || !strings.Contains(logged[0], "boom: disk") {
		t.Errorf("internal error not logged: %v", logged)
	}
}

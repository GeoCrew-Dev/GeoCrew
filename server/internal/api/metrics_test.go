package api

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestMetricsCountRoutesNotIDs(t *testing.T) {
	var s *Server
	e := newEnvWith(t, func(x *Server) { s = x })
	m := httptest.NewServer(s.MetricsHandler())
	defer m.Close()

	var leader joinResp
	if c := e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "组", "nickname": "组长"}, &leader); c != 201 {
		t.Fatalf("create: %d", c)
	}
	e.call("GET", "/api/v0/workspaces/"+leader.WorkspaceID+"/features", leader.Token, nil, nil)
	e.call("GET", "/nope", "", nil, nil)

	resp, err := http.Get(m.URL + "/metrics")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	out := string(body)
	for _, want := range []string{
		`geocrew_http_requests_total{method="POST",route="POST /api/v0/workspaces",code="201"} 1`,
		`route="GET /api/v0/workspaces/{ws}/features",code="200"} 1`,
		`route="unmatched",code="404"} 1`,
		"geocrew_workspaces 1\n",
		`geocrew_realtime_connections{kind="member"} 0`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in\n%s", want, out)
		}
	}
	if strings.Contains(out, leader.WorkspaceID) {
		t.Error("a workspace id leaked into the metrics")
	}
}

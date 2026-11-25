package metrics

import (
	"strings"
	"testing"
	"time"
)

func TestWriteCountsRequestsAndGauges(t *testing.T) {
	r := New()
	r.Request("GET", "GET /api/v0/workspaces/{ws}/features", 200, 30*time.Millisecond)
	r.Request("GET", "GET /api/v0/workspaces/{ws}/features", 200, 3*time.Second)
	r.Request("POST", "", 404, time.Millisecond)
	r.Gauge("geocrew_realtime_clients", "Open realtime connections.", func() []Sample {
		return []Sample{{Value: 3}, {Labels: []string{"kind", `a"b`}, Value: 1}}
	})
	var b strings.Builder
	if err := r.Write(&b); err != nil {
		t.Fatal(err)
	}
	out := b.String()
	for _, want := range []string{
		`geocrew_http_requests_total{method="GET",route="GET /api/v0/workspaces/{ws}/features",code="200"} 2`,
		`geocrew_http_requests_total{method="POST",route="unmatched",code="404"} 1`,
		`geocrew_http_request_duration_seconds_bucket{route="GET /api/v0/workspaces/{ws}/features",le="0.05"} 1`,
		`geocrew_http_request_duration_seconds_bucket{route="GET /api/v0/workspaces/{ws}/features",le="5"} 2`,
		`geocrew_http_request_duration_seconds_bucket{route="GET /api/v0/workspaces/{ws}/features",le="+Inf"} 2`,
		`geocrew_http_request_duration_seconds_count{route="GET /api/v0/workspaces/{ws}/features"} 2`,
		"# TYPE geocrew_realtime_clients gauge\ngeocrew_realtime_clients 3\n",
		`geocrew_realtime_clients{kind="a\"b"} 1`,
		"geocrew_uptime_seconds ",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in\n%s", want, out)
		}
	}
	var nilReg *Registry
	nilReg.Request("GET", "x", 200, 0) // a nil registry is a no-op
}

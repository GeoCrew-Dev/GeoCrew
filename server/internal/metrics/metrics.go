// Package metrics keeps a few counters and a latency histogram and writes
// them in the Prometheus text exposition format, so a self-hosted server
// can be watched with Prometheus, VictoriaMetrics or a plain curl without
// pulling in a client library.
package metrics

import (
	"fmt"
	"io"
	"math"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Buckets are the request latency bucket bounds in seconds.
var Buckets = []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10}

type series struct {
	labels string // rendered {a="b",...}
	count  uint64
	sum    float64
	bins   []uint64 // cumulative counts per bucket, histograms only
}

// Registry holds request metrics and gauges read when scraped.
type Registry struct {
	mu       sync.Mutex
	start    time.Time
	requests map[string]*series // by labels: method, route, code
	latency  map[string]*series // by labels: route
	gauges   []gauge
}

type gauge struct {
	name, help string
	read       func() []Sample
}

// Sample is one value of a gauge, with its labels as name/value pairs.
type Sample struct {
	Labels []string
	Value  float64
}

// New returns an empty registry.
func New() *Registry {
	return &Registry{start: time.Now(), requests: map[string]*series{}, latency: map[string]*series{}}
}

// Gauge registers a value read on every scrape. name must be a valid
// metric name; it is written as given.
func (r *Registry) Gauge(name, help string, read func() []Sample) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.gauges = append(r.gauges, gauge{name, help, read})
}

// Request counts one HTTP request. route is the matched pattern (with
// placeholders, never ids), so the number of series stays small.
func (r *Registry) Request(method, route string, code int, d time.Duration) {
	if r == nil {
		return
	}
	if route == "" {
		route = "unmatched"
	}
	key := labels("method", method, "route", route, "code", strconv.Itoa(code))
	lkey := labels("route", route)
	sec := d.Seconds()
	r.mu.Lock()
	defer r.mu.Unlock()
	s := r.requests[key]
	if s == nil {
		s = &series{labels: key}
		r.requests[key] = s
	}
	s.count++
	h := r.latency[lkey]
	if h == nil {
		h = &series{labels: lkey, bins: make([]uint64, len(Buckets))}
		r.latency[lkey] = h
	}
	h.count++
	h.sum += sec
	for i, b := range Buckets {
		if sec <= b {
			h.bins[i]++
		}
	}
}

// Write renders every metric in the text exposition format (version 0.0.4).
func (r *Registry) Write(w io.Writer) error {
	r.mu.Lock()
	reqs := sorted(r.requests)
	lat := sorted(r.latency)
	gauges := append([]gauge(nil), r.gauges...)
	uptime := time.Since(r.start).Seconds()
	var b strings.Builder
	b.WriteString("# HELP geocrew_http_requests_total HTTP requests by method, route pattern and status code.\n")
	b.WriteString("# TYPE geocrew_http_requests_total counter\n")
	for _, s := range reqs {
		fmt.Fprintf(&b, "geocrew_http_requests_total%s %d\n", s.labels, s.count)
	}
	b.WriteString("# HELP geocrew_http_request_duration_seconds Time to serve a request, by route pattern.\n")
	b.WriteString("# TYPE geocrew_http_request_duration_seconds histogram\n")
	for _, s := range lat {
		inner := strings.TrimSuffix(strings.TrimPrefix(s.labels, "{"), "}")
		for i, bound := range Buckets {
			fmt.Fprintf(&b, "geocrew_http_request_duration_seconds_bucket{%s,le=\"%s\"} %d\n", inner, num(bound), s.bins[i])
		}
		fmt.Fprintf(&b, "geocrew_http_request_duration_seconds_bucket{%s,le=\"+Inf\"} %d\n", inner, s.count)
		fmt.Fprintf(&b, "geocrew_http_request_duration_seconds_sum%s %s\n", s.labels, num(s.sum))
		fmt.Fprintf(&b, "geocrew_http_request_duration_seconds_count%s %d\n", s.labels, s.count)
	}
	r.mu.Unlock()

	b.WriteString("# HELP geocrew_uptime_seconds Seconds since the server started.\n# TYPE geocrew_uptime_seconds gauge\n")
	fmt.Fprintf(&b, "geocrew_uptime_seconds %s\n", num(uptime))
	// Gauges are read outside the lock: they may query the database.
	for _, g := range gauges {
		fmt.Fprintf(&b, "# HELP %s %s\n# TYPE %s gauge\n", g.name, g.help, g.name)
		for _, s := range g.read() {
			fmt.Fprintf(&b, "%s%s %s\n", g.name, labels(s.Labels...), num(s.Value))
		}
	}
	_, err := io.WriteString(w, b.String())
	return err
}

func sorted(m map[string]*series) []*series {
	out := make([]*series, 0, len(m))
	for _, s := range m {
		c := *s
		c.bins = append([]uint64(nil), s.bins...)
		out = append(out, &c)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].labels < out[j].labels })
	return out
}

// labels renders name/value pairs as {a="1",b="2"}; an odd last name is dropped.
func labels(kv ...string) string {
	if len(kv) < 2 {
		return ""
	}
	var b strings.Builder
	b.WriteByte('{')
	for i := 0; i+1 < len(kv); i += 2 {
		if i > 0 {
			b.WriteByte(',')
		}
		b.WriteString(kv[i])
		b.WriteString(`="`)
		b.WriteString(escape(kv[i+1]))
		b.WriteByte('"')
	}
	b.WriteByte('}')
	return b.String()
}

func escape(v string) string {
	return strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`).Replace(v)
}

func num(v float64) string {
	switch {
	case math.IsInf(v, 1):
		return "+Inf"
	case math.IsInf(v, -1):
		return "-Inf"
	case math.IsNaN(v):
		return "NaN"
	}
	return strconv.FormatFloat(v, 'g', -1, 64)
}

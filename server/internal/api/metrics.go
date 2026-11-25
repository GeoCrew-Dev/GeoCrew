package api

import (
	"context"
	"net/http"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/metrics"
)

// MetricsHandler serves s.Metrics at /metrics in the Prometheus text
// format, adding gauges for realtime connections and stored workspaces.
// It is meant for a separate, private listener (-metrics-addr), never the
// public one: the numbers say how busy each route is.
func (s *Server) MetricsHandler() http.Handler {
	if s.Metrics == nil {
		s.Metrics = metrics.New()
	}
	s.Metrics.Gauge("geocrew_realtime_connections", "Open realtime connections, by kind (member devices or web viewers).", func() []metrics.Sample {
		if s.Hub == nil {
			return nil
		}
		_, m, w := s.Hub.Stats()
		return []metrics.Sample{{Labels: []string{"kind", "member"}, Value: float64(m)}, {Labels: []string{"kind", "viewer"}, Value: float64(w)}}
	})
	s.Metrics.Gauge("geocrew_realtime_workspaces", "Workspaces with at least one open realtime connection.", func() []metrics.Sample {
		if s.Hub == nil {
			return nil
		}
		n, _, _ := s.Hub.Stats()
		return []metrics.Sample{{Value: float64(n)}}
	})
	s.Metrics.Gauge("geocrew_workspaces", "Workspaces stored on this server.", func() []metrics.Sample {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		list, err := s.Store.Workspaces(ctx)
		if err != nil {
			return nil
		}
		return []metrics.Sample{{Value: float64(len(list))}}
	})
	mux := http.NewServeMux()
	mux.HandleFunc("GET /metrics", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		if err := s.Metrics.Write(w); err != nil {
			s.logger().Warn("metrics", "err", err)
		}
	})
	return mux
}

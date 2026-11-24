package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// wireSync is the "sync" member added to each GeoJSON Feature.
type wireSync struct {
	Version   int64     `json:"version"`
	UpdatedAt time.Time `json:"updated_at"`
	UpdatedBy string    `json:"updated_by,omitempty"`
	Deleted   bool      `json:"deleted"`
	Seq       int64     `json:"seq,omitempty"`
}

// wireFeature is a GeoJSON Feature (RFC 7946) plus sync metadata.
type wireFeature struct {
	Type       string          `json:"type"`
	ID         string          `json:"id"`
	Geometry   json.RawMessage `json:"geometry"`
	Properties json.RawMessage `json:"properties"`
	Sync       wireSync        `json:"sync"`
}

func toWire(f store.Feature) wireFeature {
	return wireFeature{
		Type: "Feature", ID: f.ID, Geometry: nullIfEmpty(f.Geometry), Properties: nullIfEmpty(f.Properties),
		Sync: wireSync{f.Version, f.UpdatedAt, f.UpdatedBy, f.Deleted, f.Seq},
	}
}

func nullIfEmpty(b json.RawMessage) json.RawMessage {
	if len(b) == 0 {
		return json.RawMessage("null")
	}
	return b
}

var geometryTypes = map[string]bool{
	"Point": true, "MultiPoint": true, "LineString": true, "MultiLineString": true,
	"Polygon": true, "MultiPolygon": true, "GeometryCollection": true,
}

// validate checks the parts of a feature the server relies on. Properties
// are free-form so new clients can add fields without a server upgrade.
func (f wireFeature) validate() error {
	if f.Type != "Feature" {
		return fmt.Errorf("type must be Feature")
	}
	if f.ID == "" || len(f.ID) > 64 {
		return fmt.Errorf("id must be 1-64 characters")
	}
	if f.Sync.Version < 1 {
		return fmt.Errorf("sync.version must be >= 1")
	}
	if f.Sync.UpdatedAt.IsZero() {
		return fmt.Errorf("sync.updated_at is required")
	}
	if g := bytes.TrimSpace(f.Geometry); len(g) > 0 && !bytes.Equal(g, []byte("null")) {
		var head struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal(g, &head); err != nil || !geometryTypes[head.Type] {
			return fmt.Errorf("geometry must be a GeoJSON geometry or null")
		}
	}
	if p := bytes.TrimSpace(f.Properties); len(p) > 0 && p[0] != '{' && !bytes.Equal(p, []byte("null")) {
		return fmt.Errorf("properties must be an object or null")
	}
	return nil
}

type pushReq struct {
	Features []wireFeature `json:"features"`
}

type pushResult struct {
	ID      string           `json:"id"`
	Status  store.PushStatus `json:"status"`
	Seq     int64            `json:"seq"`
	Current *wireFeature     `json:"current,omitempty"`
}

type pushResp struct {
	Results []pushResult `json:"results"`
	Seq     int64        `json:"seq"`
}

func (s *Server) push(w http.ResponseWriter, r *http.Request) {
	var req pushReq
	if !decode(w, r, &req) {
		return
	}
	if len(req.Features) > s.MaxPushFeatures {
		writeErr(w, http.StatusRequestEntityTooLarge, "batch_too_large",
			fmt.Sprintf("at most %d features per push", s.MaxPushFeatures))
		return
	}
	fs := make([]store.Feature, len(req.Features))
	seen := map[string]bool{}
	for i, wf := range req.Features {
		if err := wf.validate(); err != nil {
			writeErr(w, http.StatusBadRequest, "invalid_feature", fmt.Sprintf("features[%d]: %v", i, err))
			return
		}
		if seen[wf.ID] {
			writeErr(w, http.StatusBadRequest, "invalid_feature", fmt.Sprintf("features[%d]: duplicate id %s", i, wf.ID))
			return
		}
		seen[wf.ID] = true
		fs[i] = store.Feature{
			ID: wf.ID, Geometry: wf.Geometry, Properties: wf.Properties,
			Version: wf.Sync.Version, UpdatedAt: wf.Sync.UpdatedAt, Deleted: wf.Sync.Deleted,
		}
	}
	ws := r.PathValue("ws")
	results, seq, err := s.Store.PushFeatures(r.Context(), ws, member(r).ID, fs)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	resp := pushResp{Results: make([]pushResult, len(results)), Seq: seq}
	applied := false
	for i, res := range results {
		resp.Results[i] = pushResult{ID: res.ID, Status: res.Status, Seq: res.Seq}
		if res.Current != nil {
			c := toWire(*res.Current)
			resp.Results[i].Current = &c
		}
		applied = applied || res.Status == store.PushApplied
	}
	if applied {
		s.Hub.FeatureChanged(ws, seq)
	}
	writeJSON(w, http.StatusOK, resp)
}

type pullResp struct {
	Features []wireFeature `json:"features"`
	// Seq is the cursor to pass as `since` next time.
	Seq  int64 `json:"seq"`
	More bool  `json:"more"`
}

func (s *Server) pull(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	since, err := strconv.ParseInt(q.Get("since"), 10, 64)
	if q.Get("since") == "" {
		since, err = 0, nil
	}
	if err != nil || since < 0 {
		writeErr(w, http.StatusBadRequest, "invalid", "since must be a non-negative integer")
		return
	}
	limit := s.MaxPullFeatures
	if l := q.Get("limit"); l != "" {
		n, err := strconv.Atoi(l)
		if err != nil || n < 1 {
			writeErr(w, http.StatusBadRequest, "invalid", "limit must be a positive integer")
			return
		}
		limit = min(n, s.MaxPullFeatures)
	}
	fs, more, err := s.Store.PullFeatures(r.Context(), r.PathValue("ws"), since, limit)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	resp := pullResp{Features: make([]wireFeature, len(fs)), Seq: since, More: more}
	for i, f := range fs {
		resp.Features[i] = toWire(f)
		resp.Seq = f.Seq
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) history(w http.ResponseWriter, r *http.Request) {
	fs, err := s.Store.FeatureHistory(r.Context(), r.PathValue("ws"), r.PathValue("feature"))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	out := make([]wireFeature, len(fs))
	for i, f := range fs {
		out[i] = toWire(f)
	}
	writeJSON(w, http.StatusOK, out)
}

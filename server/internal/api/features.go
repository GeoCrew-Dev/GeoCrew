package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/export"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
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
	var opts []store.PushOption
	if member(r).Role != store.RoleLeader {
		// Only leaders review records (protocol §6.9); a member's push keeps whatever review is stored.
		opts = append(opts, store.LockProps(ReviewProp))
	}
	results, seq, err := s.Store.PushFeatures(r.Context(), ws, member(r).ID, fs, opts...)
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
		s.notifyAssignments(ws, member(r).ID, fs, results)
	}
	writeJSON(w, http.StatusOK, resp)
}

// ReviewProp is the property a leader sets when reviewing a record.
const ReviewProp = "review"

type pullResp struct {
	Features []wireFeature `json:"features"`
	// Seq is the cursor to pass as `since` next time.
	Seq  int64 `json:"seq"`
	More bool  `json:"more"`
}

// page reads the since/limit cursor shared by the incremental list endpoints.
func page(w http.ResponseWriter, r *http.Request, maxLimit int) (since int64, limit int, ok bool) {
	q := r.URL.Query()
	if v := q.Get("since"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil || n < 0 {
			writeErr(w, http.StatusBadRequest, "invalid", "since must be a non-negative integer")
			return 0, 0, false
		}
		since = n
	}
	limit = maxLimit
	if v := q.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			writeErr(w, http.StatusBadRequest, "invalid", "limit must be a positive integer")
			return 0, 0, false
		}
		limit = min(n, maxLimit)
	}
	return since, limit, true
}

func (s *Server) pull(w http.ResponseWriter, r *http.Request) {
	since, limit, ok := page(w, r, s.MaxPullFeatures)
	if !ok {
		return
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

// exportGeoJSON downloads the workspace's current records as one GeoJSON
// file for desktop GIS. Members can already read everything through pull,
// so this needs no extra permission.
func (s *Server) exportGeoJSON(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	if _, err := s.Store.Workspace(r.Context(), ws); err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/geo+json")
	w.Header().Set("Content-Disposition", `attachment; filename="geocrew-export.geojson"`)
	w.Header().Set("Cache-Control", "no-store")
	if _, err := export.GeoJSON(r.Context(), s.Store, ws, w); err != nil {
		// Headers are sent already; the truncated body tells the client it failed.
		s.logger().Error("export", "workspace", ws, "err", err)
	}
}

// exportPackage downloads the workspace as a project package that the
// Android app opens as a new project: records, photos and the record form.
func (s *Server) exportPackage(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	if _, err := s.Store.Workspace(r.Context(), ws); err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="workspace.geocrew.zip"`)
	w.Header().Set("Cache-Control", "no-store")
	open := func(sha string) (io.ReadCloser, error) { return s.Files.Open(ws, sha) }
	if _, err := export.Package(r.Context(), s.Store, ws, open, s.Name+"/"+s.Version, time.Now(), w); err != nil {
		// Headers are sent already; a broken zip tells the client it failed.
		s.logger().Error("export package", "workspace", ws, "err", err)
	}
}

// exportSamples downloads the sample register (protocol v0 §6.4) as CSV.
// tz_offset (minutes east of UTC, as a browser knows it) sets the time zone
// of the sampling times; it defaults to UTC.
func (s *Server) exportSamples(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	zone, ok := tzOffset(w, r)
	if !ok {
		return
	}
	if _, err := s.Store.Workspace(r.Context(), ws); err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="samples.csv"`)
	w.Header().Set("Cache-Control", "no-store")
	if _, err := export.Samples(r.Context(), s.Store, ws, zone, w); err != nil {
		s.logger().Error("export samples", "workspace", ws, "err", err)
	}
}

// tzOffset reads the optional tz_offset query (minutes east of UTC) for
// exports that group or print by local time; UTC without it. On a bad
// value it writes the error and returns false.
func tzOffset(w http.ResponseWriter, r *http.Request) (*time.Location, bool) {
	v := r.URL.Query().Get("tz_offset")
	if v == "" {
		return time.UTC, true
	}
	m, err := strconv.Atoi(v)
	if err != nil || m < -14*60 || m > 14*60 {
		writeErr(w, http.StatusBadRequest, "bad_tz_offset", "tz_offset must be minutes east of UTC, -840 to 840")
		return nil, false
	}
	return time.FixedZone("", m*60), true
}

// exportWorkload downloads the team's work per day and member as CSV.
func (s *Server) exportWorkload(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	zone, ok := tzOffset(w, r)
	if !ok {
		return
	}
	if _, err := s.Store.Workspace(r.Context(), ws); err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="workload.csv"`)
	w.Header().Set("Cache-Control", "no-store")
	if _, err := export.Workload(r.Context(), s.Store, ws, zone, w); err != nil {
		s.logger().Error("export workload", "workspace", ws, "err", err)
	}
}

// exportKML downloads the workspace's records as KML, coloured by the shared legend.
func (s *Server) exportKML(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	if _, err := s.Store.Workspace(r.Context(), ws); err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.google-earth.kml+xml")
	w.Header().Set("Content-Disposition", `attachment; filename="geocrew-export.kml"`)
	w.Header().Set("Cache-Control", "no-store")
	if _, err := export.KML(r.Context(), s.Store, ws, w); err != nil {
		s.logger().Error("export kml", "workspace", ws, "err", err)
	}
}

// exportGPX downloads the workspace's points, lines and tracks as GPX, with
// per-fix times on tracks that carry them (protocol v0 §6.13).
func (s *Server) exportGPX(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	if _, err := s.Store.Workspace(r.Context(), ws); err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/gpx+xml")
	w.Header().Set("Content-Disposition", `attachment; filename="geocrew-export.gpx"`)
	w.Header().Set("Cache-Control", "no-store")
	if _, err := export.GPX(r.Context(), s.Store, ws, w); err != nil {
		s.logger().Error("export gpx", "workspace", ws, "err", err)
	}
}

// exportGeoPackage downloads the workspace's records as a GeoPackage for
// desktop GIS. SQLite needs a real file, so it is built in a temporary
// directory and removed once sent.
func (s *Server) exportGeoPackage(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	if _, err := s.Store.Workspace(r.Context(), ws); err != nil {
		s.fail(w, r, err)
		return
	}
	dir, err := os.MkdirTemp("", "geocrew-gpkg-")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	defer os.RemoveAll(dir)
	path := filepath.Join(dir, "export.gpkg")
	if _, err := export.GeoPackage(r.Context(), s.Store, ws, path); err != nil {
		s.logger().Error("export gpkg", "workspace", ws, "err", err)
		s.fail(w, r, err)
		return
	}
	f, err := os.Open(path)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/geopackage+sqlite3")
	w.Header().Set("Content-Disposition", `attachment; filename="geocrew-export.gpkg"`)
	w.Header().Set("Cache-Control", "no-store")
	if fi, err := f.Stat(); err == nil {
		w.Header().Set("Content-Length", strconv.FormatInt(fi.Size(), 10))
	}
	io.Copy(w, f)
}

// exportRoutes downloads the geological route table (protocol v0 §6.6) as CSV.
func (s *Server) exportRoutes(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	if _, err := s.Store.Workspace(r.Context(), ws); err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="routes.csv"`)
	w.Header().Set("Cache-Control", "no-store")
	if _, err := export.Routes(r.Context(), s.Store, ws, w); err != nil {
		s.logger().Error("export routes", "workspace", ws, "err", err)
	}
}

// checks lists records worth a second look (duplicate numbers, missing
// positions and the like). ?accuracy_m= and ?far_km= override the thresholds.
func (s *Server) checks(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	var opt export.CheckOptions
	if v, err := strconv.ParseFloat(r.URL.Query().Get("accuracy_m"), 64); err == nil && v > 0 {
		opt.AccuracyLimitM = v
	}
	if v, err := strconv.ParseFloat(r.URL.Query().Get("far_km"), 64); err == nil && v > 0 {
		opt.FarKm = v
	}
	issues, err := export.Checks(r.Context(), s.Store, ws, opt)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if issues == nil {
		issues = []export.Issue{}
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]any{"issues": issues})
}

// exportStructures downloads the structural measurement table (planar
// attitudes and lineations, protocol v0 §6.12) as CSV.
func (s *Server) exportStructures(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	if _, err := s.Store.Workspace(r.Context(), ws); err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="structures.csv"`)
	w.Header().Set("Cache-Control", "no-store")
	if _, err := export.Structures(r.Context(), s.Store, ws, w); err != nil {
		s.logger().Error("export structures", "workspace", ws, "err", err)
	}
}

// exportBoreholes downloads the borehole layer table (protocol v0 §6.11) as CSV.
func (s *Server) exportBoreholes(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	if _, err := s.Store.Workspace(r.Context(), ws); err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="boreholes.csv"`)
	w.Header().Set("Cache-Control", "no-store")
	if _, err := export.Boreholes(r.Context(), s.Store, ws, w); err != nil {
		s.logger().Error("export boreholes", "workspace", ws, "err", err)
	}
}

// exportSections downloads the measured-section layer table (protocol v0 §6.5) as CSV.
func (s *Server) exportSections(w http.ResponseWriter, r *http.Request) {
	ws := r.PathValue("ws")
	if _, err := s.Store.Workspace(r.Context(), ws); err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="sections.csv"`)
	w.Header().Set("Cache-Control", "no-store")
	if _, err := export.Sections(r.Context(), s.Store, ws, w); err != nil {
		s.logger().Error("export sections", "workspace", ws, "err", err)
	}
}

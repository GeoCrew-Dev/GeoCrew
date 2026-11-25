package export

import (
	"bytes"
	"context"
	"encoding/json"
	"encoding/xml"
	"strings"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store/sqlite"
)

func TestGPXAndTrackTimes(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, _ := db.CreateWorkspace(ctx, "北山组", "甲")
	at := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	track := `{"type":"LineString","coordinates":[[116,40,100],[116.001,40,110],[116.002,40]]}`
	fs := []store.Feature{
		{ID: "t1", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(track),
			Properties: json.RawMessage(`{"kind":"track","title":"轨迹 & 1","started_at":"2026-11-24T02:00:00.5Z","ended_at":"2026-11-24T02:00:30Z","times_s":[0,10,20]}`)},
		// Times that do not match the coordinates are not used.
		{ID: "t2", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(track),
			Properties: json.RawMessage(`{"kind":"track","title":"T2","started_at":"2026-11-24T03:00:00Z","times_s":[0,10]}`)},
		{ID: "p", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"Point","coordinates":[116.5,40,52.3]}`),
			Properties: json.RawMessage(`{"kind":"geo_point","title":"D001","note":"灰岩"}`)},
		{ID: "l", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"MultiLineString","coordinates":[[[116,40],[116,41]],[[117,40],[117,41]]]}`),
			Properties: json.RawMessage(`{"kind":"line","title":"F1"}`)},
		{ID: "a", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"Polygon","coordinates":[[[116,40],[116.1,40],[116.1,40.1],[116,40]]]}`),
			Properties: json.RawMessage(`{"kind":"polygon"}`)},
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	n, err := GPX(ctx, db, ws.ID, &buf)
	if err != nil || n != 4 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	out := buf.String()
	if err := xml.Unmarshal(buf.Bytes(), new(struct{})); err != nil {
		t.Fatalf("xml: %v\n%s", err, out)
	}
	for _, want := range []string{
		`<wpt lat="40" lon="116.5"><ele>52.3</ele><name>D001</name><desc>灰岩</desc></wpt>`,
		`<trk><name>轨迹 &amp; 1</name><trkseg><trkpt lat="40" lon="116"><ele>100</ele><time>2026-11-24T02:00:00Z</time></trkpt>`,
		`<time>2026-11-24T02:00:20Z</time>`,
		`<trk><name>T2</name><trkseg><trkpt lat="40" lon="116"><ele>100</ele></trkpt>`,
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %s in\n%s", want, out)
		}
	}
	// GPX order: waypoints, routes, tracks; a multi-line is two routes.
	if !(strings.Index(out, "<wpt") < strings.Index(out, "<rte>") && strings.Index(out, "<rte>") < strings.Index(out, "<trk>")) || strings.Count(out, "<rte>") != 2 {
		t.Errorf("order or routes wrong:\n%s", out)
	}

	buf.Reset()
	if _, err := KML(ctx, db, ws.ID, &buf); err != nil {
		t.Fatal(err)
	}
	kml := buf.String()
	if !strings.Contains(kml, "<TimeSpan><begin>2026-11-24T02:00:00Z</begin><end>2026-11-24T02:00:30Z</end></TimeSpan><ExtendedData>") ||
		!strings.Contains(kml, "<TimeSpan><begin>2026-11-24T03:00:00Z</begin></TimeSpan>") || strings.Contains(kml, "times_s") {
		t.Errorf("kml time span wrong:\n%s", kml)
	}
}

func TestTrackTimesRejectsBadLists(t *testing.T) {
	p := func(times string) map[string]any {
		var m map[string]any
		json.Unmarshal([]byte(`{"started_at":"2026-11-24T02:00:00Z","times_s":`+times+`}`), &m)
		return m
	}
	for _, bad := range []string{`[0,10,5]`, `[0,1.5,3]`, `[0,"1",3]`, `[0,1]`, `null`} {
		if got := trackTimes(p(bad), 3); got != nil {
			t.Errorf("%s: got %v", bad, got)
		}
	}
	if got := trackTimes(p(`[0,0,7]`), 3); len(got) != 3 || got[2] != "2026-11-24T02:00:07Z" {
		t.Errorf("good list: %v", got)
	}
}

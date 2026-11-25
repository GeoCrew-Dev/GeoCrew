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

func TestKML(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, _ := db.CreateWorkspace(ctx, "北山 & 组", "甲")
	if _, err := db.PutSettings(ctx, ws.ID, json.RawMessage(`{"legend":{"key":"lithology","entries":[{"value":"灰岩","color":"#64b5f6"},{"value":"x","color":"red"}]}}`), 0); err != nil {
		t.Fatal(err)
	}
	at := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	fs := []store.Feature{
		{ID: "a", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"Point","coordinates":[116.5,40,52.3]}`),
			Properties: json.RawMessage(`{"kind":"geo_point","title":"D001 <a>","note":"层理 & 节理","lithology":" 灰岩 ","dip":42,"samples":[{"no":"S1"},{"no":"S2"}]}`)},
		{ID: "b", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"Polygon","coordinates":[[[116,40],[116.1,40],[116.1,40.1],[116,40]]]}`),
			Properties: json.RawMessage(`{"kind":"polygon","lithology":"灰岩","fill":"#22a884"}`)},
		{ID: "c", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"MultiLineString","coordinates":[[[116,40],[116,41]],[[117,40],[117,41]]]}`),
			Properties: json.RawMessage(`{"kind":"line","lithology":"x"}`)},
		{ID: "d", Version: 1, UpdatedAt: at, Properties: json.RawMessage(`{"kind":"note","title":"无位置"}`)},
		{ID: "e", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"LineString","coordinates":[[116,40]]}`), Properties: json.RawMessage(`{}`)},
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	n, err := KML(ctx, db, ws.ID, &buf)
	if err != nil || n != 3 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	out := buf.String()
	// Well-formed XML whatever the titles hold.
	d := xml.NewDecoder(strings.NewReader(out))
	for {
		if _, err := d.Token(); err != nil {
			if err.Error() != "EOF" {
				t.Fatalf("xml: %v\n%s", err, out)
			}
			break
		}
	}
	for _, want := range []string{
		"<name>北山 &amp; 组</name>",
		`<Style id="c64B5F6">`, "<color>fff6b564</color>", `<Style id="c22A884">`,
		"<name>D001 &lt;a&gt;</name>", "<description>层理 &amp; 节理</description>",
		"<styleUrl>#c64B5F6</styleUrl>", "<styleUrl>#c22A884</styleUrl>",
		`<Data name="samples"><value>S1,S2</value></Data>`, `<Data name="dip"><value>42</value></Data>`,
		"<coordinates>116.5,40,52.3</coordinates>",
		"<MultiGeometry><LineString>",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %s in\n%s", want, out)
		}
	}
	if strings.Count(out, "<Style ") != 2 || strings.Contains(out, "无位置") {
		t.Errorf("styles or records wrong:\n%s", out)
	}
}

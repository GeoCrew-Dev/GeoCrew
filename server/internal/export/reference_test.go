package export

import (
	"strings"
	"testing"
)

func TestReferenceAt(t *testing.T) {
	gj := `{"type":"FeatureCollection","features":[
	 {"type":"Feature","properties":{"unit":"K1b","age":"早白垩世"},"geometry":{"type":"Polygon","coordinates":[
	   [[116.0,39.0],[116.1,39.0],[116.1,39.1],[116.0,39.1],[116.0,39.0]],
	   [[116.04,39.04],[116.06,39.04],[116.06,39.06],[116.04,39.06],[116.04,39.04]]]}},
	 {"type":"Feature","properties":{"unit":"J3"},"geometry":{"type":"MultiPolygon","coordinates":[[[[116.1,39.0],[116.2,39.0],[116.2,39.1],[116.1,39.1],[116.1,39.0]]]]}},
	 {"type":"Feature","properties":{"unit":"F3"},"geometry":{"type":"LineString","coordinates":[[116.03,38.9],[116.03,39.2]]}},
	 {"type":"Feature","properties":{"unit":"far"},"geometry":{"type":"LineString","coordinates":[[117,38.9],[117,39.2]]}},
	 {"type":"Feature","properties":{},"geometry":null}
	]}`
	layer := ReferenceLayer{ID: "r1", Name: "1:5万地质图", ColorBy: "unit"}
	areas, lines, err := ReferenceAt(layer, strings.NewReader(gj), 116.031, 39.02, 200)
	if err != nil {
		t.Fatal(err)
	}
	if len(areas) != 1 || areas[0].Value != "K1b" || areas[0].Attributes["age"] != "早白垩世" || areas[0].Layer != "1:5万地质图" {
		t.Fatalf("areas: %+v", areas)
	}
	// 0.001° of longitude at 39°N is about 86 m.
	if len(lines) != 1 || lines[0].Value != "F3" || *lines[0].DistanceM < 80 || *lines[0].DistanceM > 92 {
		t.Fatalf("lines: %+v", lines)
	}
	// Inside the hole: not in K1b.
	if areas, _, _ := ReferenceAt(layer, strings.NewReader(gj), 116.05, 39.05, 10); len(areas) != 0 {
		t.Fatalf("hole: %+v", areas)
	}
	if areas, _, _ := ReferenceAt(layer, strings.NewReader(gj), 116.15, 39.05, 10); len(areas) != 1 || areas[0].Value != "J3" {
		t.Fatalf("multipolygon: %+v", areas)
	}
	if _, _, err := ReferenceAt(layer, strings.NewReader("{"), 0, 0, 10); err == nil {
		t.Fatal("bad json must fail")
	}
	got := ReferenceLayers([]byte(`{"reference_layers":[{"id":"a","name":"图","file":"` + strings.Repeat("a", 64) + `","color_by":"unit"},{"id":"b","name":"坏","file":"x"}]}`))
	if len(got) != 1 || got[0].ColorBy != "unit" {
		t.Fatalf("layers: %+v", got)
	}
}

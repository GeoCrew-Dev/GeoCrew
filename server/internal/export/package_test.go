package export

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
)

func TestPackageMatchesTheAppFormat(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, err := db.CreateWorkspace(ctx, "北山组", "甲")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.PutSettings(ctx, ws.ID, json.RawMessage(`{"form":[{"key":"layer","label":"地层"}],"legend":{"key":"layer","entries":[{"value":"砂岩","color":"#FFEE58"}]},"other":1}`), 0); err != nil {
		t.Fatal(err)
	}
	have := strings.Repeat("a", 64)
	lost := strings.Repeat("b", 64)
	voice := strings.Repeat("c", 64)
	at := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	fs := []store.Feature{
		{ID: "p1", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"Point","coordinates":[116,40]}`),
			Properties: json.RawMessage(`{"title":"D001","photos":["` + have + `","` + lost + `","../evil"]}`)},
		{ID: "p2", Version: 1, UpdatedAt: at, Deleted: true, Properties: json.RawMessage(`{"photos":["` + have + `"]}`)},
		{ID: "p3", Version: 1, UpdatedAt: at, Properties: json.RawMessage(`{"title":"文字记录","photos":["` + have + `"],"audio":["` + voice + `"]}`)},
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	open := func(sha string) (io.ReadCloser, error) {
		switch sha {
		case have:
			return io.NopCloser(strings.NewReader("JPEG")), nil
		case voice:
			return io.NopCloser(strings.NewReader("M4A")), nil
		}
		return nil, os.ErrNotExist
	}
	var buf bytes.Buffer
	sum, err := Package(ctx, db, ws.ID, open, "test/1", at, &buf)
	if err != nil {
		t.Fatal(err)
	}
	if sum != (PackageSummary{Features: 2, Photos: 1, Audio: 1, Missing: 1}) {
		t.Fatalf("summary %+v", sum)
	}

	z, err := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
	if err != nil {
		t.Fatal(err)
	}
	files := map[string]string{}
	for _, f := range z.File {
		r, _ := f.Open()
		b, _ := io.ReadAll(r)
		r.Close()
		files[f.Name] = string(b)
	}
	if len(files) != 4 || files["photos/"+have+".jpg"] != "JPEG" || files["audio/"+voice+".m4a"] != "M4A" {
		t.Fatalf("entries %v", keys(files))
	}
	var man struct {
		Format  string `json:"format"`
		Version int    `json:"version"`
		Name    string `json:"name"`
		Config  struct {
			Fields []struct {
				Key string `json:"key"`
			} `json:"fields"`
			Legend struct {
				Key string `json:"key"`
			} `json:"legend"`
		} `json:"config"`
	}
	if err := json.Unmarshal([]byte(files["project.json"]), &man); err != nil {
		t.Fatal(err)
	}
	if man.Format != PackageFormat || man.Version != 1 || man.Name != "北山组" || len(man.Config.Fields) != 1 || man.Config.Fields[0].Key != "layer" || man.Config.Legend.Key != "layer" {
		t.Fatalf("manifest %+v", man)
	}
	var fc struct {
		Features []struct {
			ID string `json:"id"`
		} `json:"features"`
	}
	if err := json.Unmarshal([]byte(files["features.geojson"]), &fc); err != nil || len(fc.Features) != 2 {
		t.Fatalf("features %v %s", err, files["features.geojson"])
	}
}

func keys(m map[string]string) []string {
	var out []string
	for k := range m {
		out = append(out, k)
	}
	return out
}

func TestRefsCoverEveryKind(t *testing.T) {
	a, b := strings.Repeat("a", 64), strings.Repeat("b", 64)
	got := Refs(json.RawMessage(`{"photos":["` + a + `","` + a + `",3],"audio":["` + b + `","` + a + `"],"note":"x"}`))
	if len(got) != 2 || got[0].SHA != a || got[0].Kind.Dir != "photos" || got[1].SHA != b || got[1].Kind.Ext != ".m4a" {
		t.Fatalf("refs %+v", got)
	}
	if Refs(json.RawMessage(`{"photos":"`+a+`"}`)) != nil || Refs(nil) != nil {
		t.Fatal("non-arrays should give nothing")
	}
}

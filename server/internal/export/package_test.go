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
	if _, err := db.PutSettings(ctx, ws.ID, json.RawMessage(`{"form":[{"key":"layer","label":"地层"}],"other":1}`), 0); err != nil {
		t.Fatal(err)
	}
	have := strings.Repeat("a", 64)
	lost := strings.Repeat("b", 64)
	at := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	fs := []store.Feature{
		{ID: "p1", Version: 1, UpdatedAt: at, Geometry: json.RawMessage(`{"type":"Point","coordinates":[116,40]}`),
			Properties: json.RawMessage(`{"title":"D001","photos":["` + have + `","` + lost + `","../evil"]}`)},
		{ID: "p2", Version: 1, UpdatedAt: at, Deleted: true, Properties: json.RawMessage(`{"photos":["` + have + `"]}`)},
		{ID: "p3", Version: 1, UpdatedAt: at, Properties: json.RawMessage(`{"title":"文字记录","photos":["` + have + `"]}`)},
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	open := func(sha string) (io.ReadCloser, error) {
		if sha == have {
			return io.NopCloser(strings.NewReader("JPEG")), nil
		}
		return nil, os.ErrNotExist
	}
	var buf bytes.Buffer
	sum, err := Package(ctx, db, ws.ID, open, "test/1", at, &buf)
	if err != nil {
		t.Fatal(err)
	}
	if sum != (PackageSummary{Features: 2, Photos: 1, Missing: 1}) {
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
	if len(files) != 3 || files["photos/"+have+".jpg"] != "JPEG" {
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
		} `json:"config"`
	}
	if err := json.Unmarshal([]byte(files["project.json"]), &man); err != nil {
		t.Fatal(err)
	}
	if man.Format != PackageFormat || man.Version != 1 || man.Name != "北山组" || len(man.Config.Fields) != 1 || man.Config.Fields[0].Key != "layer" {
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

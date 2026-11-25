package export

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
)

func TestChecks(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, _ := db.CreateWorkspace(ctx, "组", "甲")
	at := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	pt := func(lon float64) json.RawMessage {
		return json.RawMessage(fmt.Sprintf(`{"type":"Point","coordinates":[%g,40]}`, lon))
	}
	fs := []store.Feature{
		{ID: "a", Geometry: pt(116.001), Properties: json.RawMessage(`{"kind":"geo_point","title":"D001","samples":[{"no":"S1"},{"no":"S2"}]}`)},
		{ID: "b", Geometry: pt(116.002), Properties: json.RawMessage(`{"kind":"geo_point","title":"d001","samples":[{"no":"s1"}]}`)},
		{ID: "c", Properties: json.RawMessage(`{"kind":"geo_point","title":"D002"}`)},
		{ID: "d", Geometry: pt(116.003), Properties: json.RawMessage(`{"kind":"geo_point","title":"D003","accuracy_m":65}`)},
		{ID: "e", Geometry: pt(120), Properties: json.RawMessage(`{"kind":"geo_point","title":"D004"}`)},
		{ID: "f", Geometry: pt(116.004), Properties: json.RawMessage(`{"kind":"geo_point","title":"D005","review":{"state":"redo","note":"补照片"}}`)},
		{ID: "g", Geometry: pt(116.005), Properties: json.RawMessage(`{"kind":"line","title":"断层"}`)},
		{ID: "h", Geometry: pt(116.006), Properties: json.RawMessage(`{"kind":"line","title":"断层"}`)},
		{ID: "x", Deleted: true, Geometry: pt(116), Properties: json.RawMessage(`{"kind":"geo_point","title":"D006","samples":[{"no":"S2"}]}`)},
		{ID: "r", Properties: json.RawMessage(`{"kind":"route","title":"L001","route":{"stops":[{"feature":"a"},{"feature":"x"},{"feature":"gone"}]}}`)},
	}
	for i := range fs {
		fs[i].Version, fs[i].UpdatedAt = 1, at
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	issues, err := Checks(ctx, db, ws.ID, CheckOptions{})
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, i := range issues {
		got = append(got, i.Feature+":"+i.Code)
	}
	want := []string{
		"a:duplicate_number", "a:duplicate_sample", "b:duplicate_number", "b:duplicate_sample",
		"c:no_position", "d:poor_accuracy", "e:far_away", "f:redo", "r:missing_stop",
	}
	if strings.Join(got, " ") != strings.Join(want, " ") {
		t.Fatalf("issues = %v", got)
	}
	if !strings.Contains(issues[1].Message, "d001") || !strings.Contains(issues[8].Message, "2 个") || issues[7].Message != "组长退回：补照片" {
		t.Fatalf("messages: %q / %q", issues[1].Message, issues[7].Message)
	}
}

func TestChecksRecordRules(t *testing.T) {
	ctx := context.Background()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	ws, m, _, _ := db.CreateWorkspace(ctx, "组", "甲")
	if _, err := db.PutSettings(ctx, ws.ID, json.RawMessage(`{"record_rules":{"required":["lithology","attitude","bogus"],"min_photos":2}}`), -1); err != nil {
		t.Fatal(err)
	}
	pt := json.RawMessage(`{"type":"Point","coordinates":[116,40]}`)
	fs := []store.Feature{
		{ID: "a", Geometry: pt, Properties: json.RawMessage(`{"kind":"geo_point","title":"D001","lithology":"灰岩","strike":30,"dip":40,"photos":["x","y"]}`)},
		{ID: "b", Geometry: pt, Properties: json.RawMessage(`{"kind":"geo_point","title":"D002","lithology":" ","dip":40,"photos":["x"]}`)},
		{ID: "c", Geometry: pt, Properties: json.RawMessage(`{"kind":"line","title":"界线"}`)},
	}
	for i := range fs {
		fs[i].Version, fs[i].UpdatedAt = 1, time.Date(2026, 11, 25, 0, 0, 0, 0, time.UTC)
	}
	if _, _, err := db.PushFeatures(ctx, ws.ID, m.ID, fs); err != nil {
		t.Fatal(err)
	}
	issues, err := Checks(ctx, db, ws.ID, CheckOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(issues) != 1 || issues[0].Feature != "b" || issues[0].Code != IssueIncomplete ||
		issues[0].Message != "本工程要求填：岩性、产状、照片（至少 2 张，现有 1 张）" {
		t.Fatalf("issues = %+v", issues)
	}
}

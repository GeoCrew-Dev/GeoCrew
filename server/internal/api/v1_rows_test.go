package api

import (
	"encoding/json"
	"strconv"
	"testing"
	"time"
)

type v1PushResp struct {
	Results []struct {
		ID        string            `json:"id"`
		Status    string            `json:"status"`
		Seq       int64             `json:"seq"`
		Conflicts []json.RawMessage `json:"conflicts"`
		Error     string            `json:"error"`
	} `json:"results"`
	Seq int64 `json:"seq"`
}

type v1PullResp struct {
	Layers []map[string]any `json:"layers"`
	Rows   []struct {
		ID         string                     `json:"id"`
		Conflicted bool                       `json:"conflicted"`
		Fields     map[string]json.RawMessage `json:"fields"`
	} `json:"rows"`
	Conflicts []struct {
		ID   string `json:"id"`
		Kind string `json:"kind"`
		Row  string `json:"row"`
	} `json:"conflicts"`
	Seq  int64 `json:"seq"`
	More bool  `json:"more"`
}

const (
	v1LayerID = "4f0e5a52-8a1e-4d55-9c1b-0d6e2f8b7a10"
	v1RowID   = "8d3c6f0e-0c1a-4d7e-9a43-3f1d9b0f4e21"
)

func v1Change(id, row string, base int64, value string, at time.Time) map[string]any {
	return map[string]any{"id": id, "layer": v1LayerID, "row": row, "base_seq": base,
		"set": map[string]any{"f1": map[string]any{"value": value, "edited_at": at.Format(time.RFC3339)}}}
}

func TestV1LayersPushPullAndConflicts(t *testing.T) {
	e := newEnvWith(t, func(s *Server) { s.MaxPushFeatures = 2 })
	var leader, member v1JoinedResp
	e.call("POST", "/api/v1/workspaces", "", map[string]string{"name": "协同", "nickname": "组长", "device": "a"}, &leader)
	e.call("POST", "/api/v1/join", "", map[string]string{"invite_code": leader.Workspace.InviteCode, "nickname": "组员", "device": "b"}, &member)
	ws := "/api/v1/workspaces/" + leader.Workspace.ID

	layer := map[string]any{"id": v1LayerID, "name": "野外点", "geometry_type": "Point", "crs": "EPSG:4490",
		"fields": []map[string]any{{"id": "f1", "name": "点名", "type": "string"}}}
	var fail v1Error
	if c := e.call("POST", ws+"/layers", member.Token, layer, &fail); c != 403 || fail.Permission != "manage_layers" {
		t.Fatalf("member creates a layer: %d %+v", c, fail)
	}
	var made map[string]any
	if c := e.call("POST", ws+"/layers", leader.Token, layer, &made); c != 200 || made["schema_version"] != 1.0 {
		t.Fatalf("create layer: %d %v", c, made)
	}
	if name, _ := made["name"].(map[string]any); name["value"] != "野外点" {
		t.Fatalf("the name is a merged property: %v", made)
	}
	if c := e.call("POST", ws+"/layers", leader.Token, layer, nil); c != 200 {
		t.Fatalf("a retry returns the layer: %d", c)
	}
	layer["geometry_type"] = "LineString"
	var stale struct {
		Error string         `json:"error"`
		Layer map[string]any `json:"layer"`
	}
	if c := e.call("POST", ws+"/layers", leader.Token, layer, &stale); c != 409 || stale.Error != "stale" || stale.Layer["geometry_type"] != "Point" {
		t.Fatalf("same id, another schema: %d %+v", c, stale)
	}

	// The leader edits a row; the member, offline from before, edited the same field earlier.
	now := time.Now().UTC()
	var pushed v1PushResp
	if c := e.call("POST", ws+"/rows", leader.Token, map[string]any{"client_now": now, "changes": []any{v1Change("00000000-0000-4000-8000-000000000001", v1RowID, 0, "D001", now.Add(-time.Minute))}}, &pushed); c != 200 ||
		len(pushed.Results) != 1 || pushed.Results[0].Status != "applied" || pushed.Results[0].Conflicts == nil {
		t.Fatalf("push: %d %+v", c, pushed)
	}
	if e.call("POST", ws+"/rows", leader.Token, map[string]any{"client_now": now, "changes": []any{v1Change("00000000-0000-4000-8000-000000000001", v1RowID, 0, "D001", now.Add(-time.Minute))}}, &pushed); pushed.Results[0].Status != "duplicate" {
		t.Fatalf("a retry is a duplicate: %+v", pushed)
	}
	e.call("POST", ws+"/rows", member.Token, map[string]any{"client_now": now, "changes": []any{v1Change("00000000-0000-4000-8000-000000000002", v1RowID, 0, "D009", now.Add(-time.Hour))}}, &pushed)
	if pushed.Results[0].Status != "applied" || len(pushed.Results[0].Conflicts) != 1 {
		t.Fatalf("the earlier edit loses into a conflict: %+v", pushed)
	}

	var pulled v1PullResp
	if c := e.call("GET", ws+"/changes?since=0", member.Token, nil, &pulled); c != 200 || len(pulled.Layers) != 1 || len(pulled.Rows) != 1 ||
		!pulled.Rows[0].Conflicted || len(pulled.Conflicts) != 1 || pulled.Conflicts[0].Kind != "value" || pulled.More {
		t.Fatalf("pull: %d %+v", c, pulled)
	}
	var value struct {
		Value string `json:"value"`
	}
	json.Unmarshal(pulled.Rows[0].Fields["f1"], &value)
	if value.Value != "D001" {
		t.Fatalf("the later edit is kept: %s", pulled.Rows[0].Fields["f1"])
	}
	var resolved struct {
		Conflict struct {
			Resolved bool `json:"resolved"`
		} `json:"conflict"`
		Result *struct {
			Status string `json:"status"`
		} `json:"result"`
	}
	if c := e.call("POST", ws+"/conflicts/"+pulled.Conflicts[0].ID+"/resolve", member.Token, map[string]string{"choose": "lost"}, &resolved); c != 200 ||
		!resolved.Conflict.Resolved || resolved.Result == nil || resolved.Result.Status != "applied" {
		t.Fatalf("resolve: %d %+v", c, resolved)
	}
	if c := e.call("GET", ws+"/changes?since="+strconv.FormatInt(pulled.Seq, 10), member.Token, nil, &pulled); c != 200 || len(pulled.Rows) != 1 {
		t.Fatalf("pull since: %d %+v", c, pulled)
	}
	json.Unmarshal(pulled.Rows[0].Fields["f1"], &value)
	if value.Value != "D009" || pulled.Rows[0].Conflicted {
		t.Fatalf("choosing lost puts it back: %+v", pulled.Rows[0])
	}

	// Request errors.
	for _, bad := range []struct {
		body   map[string]any
		status int
		code   string
	}{
		{map[string]any{"changes": []any{}}, 400, "invalid"},
		{map[string]any{"client_now": now, "changes": []any{map[string]any{"id": "00000000-0000-4000-8000-000000000009", "layer": v1LayerID, "row": v1RowID,
			"set": map[string]any{"f1": map[string]any{"value": "no time"}}}}}, 400, "invalid"},
		{map[string]any{"client_now": now, "changes": []any{v1Change("00000000-0000-4000-8000-000000000003", v1RowID, 0, "a", now), v1Change("00000000-0000-4000-8000-000000000004", v1RowID, 0, "b", now)}}, 400, "invalid"},
		{map[string]any{"client_now": now, "changes": []any{v1Change("00000000-0000-4000-8000-000000000005", v1RowID, 0, "a", now), v1Change("00000000-0000-4000-8000-000000000006", v1RowID, 0, "b", now), v1Change("00000000-0000-4000-8000-000000000007", v1RowID, 0, "c", now)}}, 413, "batch_too_large"},
	} {
		if c := e.call("POST", ws+"/rows", member.Token, bad.body, &fail); c != bad.status || fail.Error != bad.code {
			t.Fatalf("push %v: %d %+v", bad.body, c, fail)
		}
	}
	if c := e.call("GET", ws+"/changes?since=-1", member.Token, nil, nil); c != 400 {
		t.Fatalf("negative since: %d", c)
	}

	// Schema changes are versioned.
	ops := map[string]any{"base_version": 1, "ops": []any{map[string]any{"op": "rename_field", "id": "f1", "name": "点号"}}}
	if c := e.call("POST", ws+"/layers/"+v1LayerID+"/schema", member.Token, ops, &fail); c != 403 || fail.Permission != "edit_schema" {
		t.Fatalf("member changes the schema: %d %+v", c, fail)
	}
	if c := e.call("POST", ws+"/layers/"+v1LayerID+"/schema", leader.Token, ops, &made); c != 200 || made["schema_version"] != 2.0 {
		t.Fatalf("schema change: %d %v", c, made)
	}
	if c := e.call("POST", ws+"/layers/"+v1LayerID+"/schema", leader.Token, ops, &stale); c != 409 || stale.Layer["schema_version"] != 2.0 {
		t.Fatalf("stale schema change: %d %+v", c, stale)
	}

	// A deleted layer takes no changes until restored.
	if c := e.call("DELETE", ws+"/layers/"+v1LayerID, leader.Token, nil, &made); c != 200 || made["deleted"] == nil {
		t.Fatalf("delete layer: %d %v", c, made)
	}
	e.call("POST", ws+"/rows", member.Token, map[string]any{"client_now": now, "changes": []any{v1Change("00000000-0000-4000-8000-000000000008", v1RowID, 0, "x", now)}}, &pushed)
	if pushed.Results[0].Status != "rejected" || pushed.Results[0].Error != "unknown_layer" {
		t.Fatalf("push to a deleted layer: %+v", pushed)
	}
	if c := e.call("POST", ws+"/layers/"+v1LayerID+"/restore", leader.Token, nil, nil); c != 200 {
		t.Fatalf("restore layer: %d", c)
	}
}

func TestV1SchemaAddsOnlyAndNamesTaken(t *testing.T) {
	e := newEnv(t)
	var leader v1JoinedResp
	e.call("POST", "/api/v1/workspaces", "", map[string]string{"name": "结构", "nickname": "组长", "device": "a"}, &leader)
	ws := "/api/v1/workspaces/" + leader.Workspace.ID
	layer := map[string]any{"id": v1LayerID, "name": "野外点", "geometry_type": "Point", "crs": "EPSG:4490",
		"fields": []map[string]any{{"id": "f1", "name": "点名", "type": "string"}}}
	if c := e.call("POST", ws+"/layers", leader.Token, layer, nil); c != 200 {
		t.Fatalf("layer: %d", c)
	}
	schema := ws + "/layers/" + v1LayerID + "/schema"
	add := func(base int, id, name, typ string) map[string]any {
		return map[string]any{"base_version": base, "ops": []map[string]any{{"op": "add_field", "field": map[string]any{"id": id, "name": name, "type": typ}}}}
	}
	// Made offline from version 0: adds only go onto the structure as it is.
	var got struct {
		SchemaVersion int64 `json:"schema_version"`
	}
	if c := e.call("POST", schema, leader.Token, add(0, "f2", "岩性", "string"), &got); c != 200 || got.SchemaVersion != 2 {
		t.Fatalf("adds only: %d %+v", c, got)
	}
	if c := e.call("POST", schema, leader.Token, add(0, "f2", "岩性", "string"), &got); c != 200 || got.SchemaVersion != 2 {
		t.Fatalf("sent again: %d %+v", c, got)
	}
	var fail struct {
		Error string `json:"error"`
		Field struct {
			ID   string `json:"id"`
			Type string `json:"type"`
		} `json:"field"`
	}
	if c := e.call("POST", schema, leader.Token, add(2, "f2", "岩性", "integer"), &fail); c != 409 || fail.Error != "id_taken" {
		t.Fatalf("the id defined otherwise: %d %+v", c, fail)
	}
	if c := e.call("POST", schema, leader.Token, add(2, "f3", " 岩性 ", "integer"), &fail); c != 409 || fail.Error != "name_taken" || fail.Field.ID != "f2" || fail.Field.Type != "string" {
		t.Fatalf("a name taken: %d %+v", c, fail)
	}
	rename := map[string]any{"base_version": 0, "ops": []map[string]any{{"op": "rename_field", "id": "f2", "name": "岩石"}}}
	if c := e.call("POST", schema, leader.Token, rename, &fail); c != 409 || fail.Error != "stale" {
		t.Fatalf("a rename goes by version: %d %+v", c, fail)
	}
}

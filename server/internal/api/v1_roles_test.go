package api

import (
	"encoding/json"
	"strings"
	"testing"
)

type v1RoleResp struct {
	ID          string         `json:"id"`
	Name        string         `json:"name"`
	Position    int            `json:"position"`
	Permissions map[string]any `json:"permissions"`
	Version     int64          `json:"version"`
}

func TestV1RolesOverridesAndAudit(t *testing.T) {
	e := newEnv(t)
	var info struct {
		Capabilities []string `json:"capabilities"`
	}
	e.call("GET", "/api/v1/info", "", nil, &info)
	if !strings.Contains(strings.Join(info.Capabilities, ","), "sync") {
		t.Fatalf("sync is served: %v", info.Capabilities)
	}
	var leader, a v1JoinedResp
	e.call("POST", "/api/v1/workspaces", "", map[string]string{"name": "身份", "nickname": "组长", "device": "S24"}, &leader)
	ws := "/api/v1/workspaces/" + leader.Workspace.ID
	e.call("POST", "/api/v1/join", "", map[string]string{"invite_code": leader.Workspace.InviteCode, "nickname": "甲", "device": "Mate"}, &a)

	var roles []v1RoleResp
	if c := e.call("GET", ws+"/roles", a.Token, nil, &roles); c != 200 || len(roles) != 1 || roles[0].ID != "member" || roles[0].Permissions["edit_rows"] != "all" {
		t.Fatalf("roles: %d %+v", c, roles)
	}
	var fail v1Error
	newRole := map[string]any{"name": "采样员", "position": 5, "permissions": map[string]any{"edit_rows": "own", "export_data": true}}
	if c := e.call("POST", ws+"/roles", a.Token, newRole, &fail); c != 403 || fail.Permission != "manage_roles" {
		t.Fatalf("member makes a role: %d %+v", c, fail)
	}
	if c := e.call("POST", ws+"/roles", leader.Token, map[string]any{"name": "x", "position": 5, "permissions": map[string]any{"fly": true}}, nil); c != 400 {
		t.Fatalf("unknown permission: %d", c)
	}
	var role v1RoleResp
	if c := e.call("POST", ws+"/roles", leader.Token, newRole, &role); c != 201 || role.Permissions["edit_rows"] != "own" || role.Permissions["export_data"] != true {
		t.Fatalf("make a role: %d %+v", c, role)
	}
	var changed v1RoleResp
	if c := e.call("PATCH", ws+"/roles/"+role.ID, leader.Token, map[string]any{"name": "采样组", "base_version": role.Version}, &changed); c != 200 || changed.Name != "采样组" || changed.Version == role.Version {
		t.Fatalf("change a role: %d %+v", c, changed)
	}
	var stale struct {
		Error string     `json:"error"`
		Role  v1RoleResp `json:"role"`
	}
	if c := e.call("PATCH", ws+"/roles/"+role.ID, leader.Token, map[string]any{"name": "旧", "base_version": role.Version}, &stale); c != 409 || stale.Error != "stale" || stale.Role.Name != "采样组" {
		t.Fatalf("change from an old version: %d %+v", c, stale)
	}
	if c := e.call("DELETE", ws+"/roles/member", leader.Token, nil, nil); c != 400 {
		t.Fatalf("delete the base role: %d", c)
	}

	// Overrides on a layer: read by those who see it, set by those who can manage the role.
	layer := map[string]any{"id": v1LayerID, "name": "野外点", "geometry_type": "Point", "crs": "EPSG:4490", "fields": []any{}}
	e.call("POST", ws+"/layers", leader.Token, layer, nil)
	if c := e.call("PUT", ws+"/layers/"+v1LayerID+"/overrides/"+role.ID, a.Token, map[string]any{"edit_rows": "none"}, &fail); c != 403 {
		t.Fatalf("member sets an override: %d", c)
	}
	if c := e.call("PUT", ws+"/layers/"+v1LayerID+"/overrides/"+role.ID, leader.Token, map[string]any{"edit_rows": "none"}, nil); c != 204 {
		t.Fatalf("set an override: %d", c)
	}
	var overrides map[string]map[string]any
	if c := e.call("GET", ws+"/layers/"+v1LayerID+"/overrides", a.Token, nil, &overrides); c != 200 || overrides[role.ID]["edit_rows"] != "none" {
		t.Fatalf("overrides: %d %+v", c, overrides)
	}
	// Hidden from the base role, the layer and its overrides are not there for the member.
	e.call("PUT", ws+"/layers/"+v1LayerID+"/overrides/member", leader.Token, map[string]any{"view_layer": false}, nil)
	if c := e.call("GET", ws+"/layers/"+v1LayerID+"/overrides", a.Token, nil, nil); c != 404 {
		t.Fatalf("overrides of a layer the member cannot see: %d", c)
	}
	if c := e.call("DELETE", ws+"/roles/"+role.ID, leader.Token, nil, nil); c != 204 {
		t.Fatalf("delete a role: %d", c)
	}

	// The audit log, newest first, for the leader alone.
	if c := e.call("GET", ws+"/audit", a.Token, nil, nil); c != 403 {
		t.Fatalf("member reads the audit log: %d", c)
	}
	var audit []struct {
		ID     int64  `json:"id"`
		Action string `json:"action"`
	}
	if c := e.call("GET", ws+"/audit?limit=2", leader.Token, nil, &audit); c != 200 || len(audit) != 2 || audit[0].Action != "delete_role" || audit[0].ID < audit[1].ID {
		t.Fatalf("audit: %d %+v", c, audit)
	}
	var older []struct {
		ID int64 `json:"id"`
	}
	if c := e.call("GET", ws+"/audit?before="+jsonNumber(audit[1].ID), leader.Token, nil, &older); c != 200 || len(older) == 0 || older[0].ID >= audit[1].ID {
		t.Fatalf("audit before: %d %+v", c, older)
	}
	if c := e.call("GET", ws+"/audit?limit=101", leader.Token, nil, nil); c != 400 {
		t.Fatalf("limit over 100: %d", c)
	}
}

func jsonNumber(n int64) string {
	b, _ := json.Marshal(n)
	return string(b)
}

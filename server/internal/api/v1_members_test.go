package api

import (
	"context"
	"testing"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

func TestV1MembersAndDevices(t *testing.T) {
	var store v1.Store
	e := newEnvWith(t, func(s *Server) { store = s.V1 })
	var leader, a, b v1JoinedResp
	e.call("POST", "/api/v1/workspaces", "", map[string]string{"name": "成员", "nickname": "组长", "device": "S24"}, &leader)
	ws := "/api/v1/workspaces/" + leader.Workspace.ID
	e.call("POST", "/api/v1/join", "", map[string]string{"invite_code": leader.Workspace.InviteCode, "nickname": "甲", "device": "Mate"}, &a)
	e.call("POST", "/api/v1/join", "", map[string]string{"invite_code": leader.Workspace.InviteCode, "nickname": "乙", "device": "Pixel"}, &b)

	var members []v1MemberJSON
	if c := e.call("GET", ws+"/members", a.Token, nil, &members); c != 200 || len(members) != 3 || members[1].Roles == nil {
		t.Fatalf("members: %d %+v", c, members)
	}

	// Nicknames are one's own; roles need someone who can manage the member.
	var m v1MemberJSON
	if c := e.call("PATCH", ws+"/members/"+a.Member.ID, a.Token, map[string]any{"nickname": " 甲二 "}, &m); c != 200 || m.Nickname != "甲二" {
		t.Fatalf("own nickname: %d %+v", c, m)
	}
	var fail v1Error
	if c := e.call("PATCH", ws+"/members/"+a.Member.ID, leader.Token, map[string]any{"nickname": "改"}, &fail); c != 403 || fail.Error != "forbidden" {
		t.Fatalf("someone else's nickname: %d %+v", c, fail)
	}
	if c := e.call("PATCH", ws+"/members/"+a.Member.ID, a.Token, map[string]any{}, nil); c != 400 {
		t.Fatalf("nothing to change: %d", c)
	}
	role, err := store.CreateRole(context.Background(), leader.Workspace.ID, leader.Member.ID, "副组长", 10, map[string]perm.Level{perm.ManageMembers: perm.All})
	if err != nil {
		t.Fatal(err)
	}
	if c := e.call("PATCH", ws+"/members/"+a.Member.ID, leader.Token, map[string]any{"roles": []string{role.ID}}, &m); c != 200 || len(m.Roles) != 1 {
		t.Fatalf("give a role: %d %+v", c, m)
	}
	// A role at one's own rank cannot be given (protocol 4.5).
	if c := e.call("PATCH", ws+"/members/"+b.Member.ID, a.Token, map[string]any{"roles": []string{role.ID}}, &fail); c != 403 || fail.Permission != "manage_members" {
		t.Fatalf("give a role at one's own rank: %d %+v", c, fail)
	}

	// Devices: one's own, and a member one can manage; a transfer code signs a new phone in.
	var devices []v1DeviceJSON
	if c := e.call("GET", ws+"/members/"+b.Member.ID+"/devices", b.Token, nil, &devices); c != 200 || len(devices) != 1 || devices[0].Device != "Pixel" {
		t.Fatalf("own devices: %d %+v", c, devices)
	}
	if c := e.call("GET", ws+"/members/"+a.Member.ID+"/devices", b.Token, nil, nil); c != 403 {
		t.Fatalf("someone else's devices: %d", c)
	}
	if c := e.call("POST", ws+"/members/"+b.Member.ID+"/transfer-code", a.Token, nil, &fail); c != 403 {
		t.Fatalf("a transfer code for someone else: %d", c)
	}
	var code struct {
		Code      string `json:"code"`
		ExpiresAt string `json:"expires_at"`
	}
	if c := e.call("POST", ws+"/members/"+b.Member.ID+"/transfer-code", b.Token, nil, &code); c != 201 || len(code.Code) != 12 || code.ExpiresAt == "" {
		t.Fatalf("transfer code: %d %+v", c, code)
	}
	var moved v1JoinedResp
	if c := e.call("POST", "/api/v1/join", "", map[string]string{"transfer_code": code.Code, "device": "新手机"}, &moved); c != 200 || moved.Member.ID != b.Member.ID {
		t.Fatalf("join with the code: %d %+v", c, moved)
	}
	e.call("GET", ws+"/members/"+b.Member.ID+"/devices", b.Token, nil, &devices)
	if len(devices) != 2 {
		t.Fatalf("the new phone is a second device: %+v", devices)
	}
	if c := e.call("DELETE", ws+"/devices/"+devices[0].ID, moved.Token, nil, nil); c != 204 {
		t.Fatalf("revoke the old phone: %d", c)
	}
	if c := e.call("GET", ws, b.Token, nil, nil); c != 401 {
		t.Fatalf("a revoked device's token: %d", c)
	}

	// The leader hands over; the new leader cannot leave without handing over in turn.
	if c := e.call("POST", ws+"/leader", a.Token, map[string]string{"member": a.Member.ID}, &fail); c != 403 {
		t.Fatalf("someone else takes the lead: %d", c)
	}
	var after struct {
		Leader string `json:"leader"`
	}
	if c := e.call("POST", ws+"/leader", leader.Token, map[string]string{"member": a.Member.ID}, &after); c != 200 || after.Leader != a.Member.ID {
		t.Fatalf("hand over: %d %+v", c, after)
	}
	if c := e.call("DELETE", ws+"/members/"+a.Member.ID, a.Token, nil, &fail); c != 409 || fail.Error != "leader_must_transfer" {
		t.Fatalf("the leader leaves: %d %+v", c, fail)
	}
	if c := e.call("DELETE", ws+"/members/"+leader.Member.ID, leader.Token, nil, nil); c != 204 {
		t.Fatalf("the old leader leaves: %d", c)
	}
	if c := e.call("GET", ws, leader.Token, nil, nil); c != 401 {
		t.Fatalf("a member who left: %d", c)
	}
}

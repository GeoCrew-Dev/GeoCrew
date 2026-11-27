package api

import (
	"context"
	"strings"
	"testing"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

type v1JoinedResp struct {
	Workspace struct {
		ID            string  `json:"id"`
		Name          string  `json:"name"`
		Leader        string  `json:"leader"`
		ArchivedAt    *string `json:"archived_at"`
		InviteCode    string  `json:"invite_code"`
		AccessVersion int64   `json:"access_version"`
	} `json:"workspace"`
	Member struct {
		ID       string   `json:"id"`
		Nickname string   `json:"nickname"`
		Roles    []string `json:"roles"`
	} `json:"member"`
	Token string `json:"token"`
}

type v1Error struct {
	Error      string `json:"error"`
	Permission string `json:"permission"`
}

func TestV1Info(t *testing.T) {
	e := newEnv(t)
	var info struct {
		Protocol     int      `json:"protocol"`
		Capabilities []string `json:"capabilities"`
		Limits       struct {
			MaxPushBatch int   `json:"max_push_batch"`
			MaxPullBatch int   `json:"max_pull_batch"`
			MaxFileSize  int64 `json:"max_file_size"`
		} `json:"limits"`
	}
	if c := e.call("GET", "/api/v1/info", "", nil, &info); c != 200 || info.Protocol != 1 {
		t.Fatalf("info: %d %+v", c, info)
	}
	if !strings.Contains(strings.Join(info.Capabilities, ","), "create") || info.Limits.MaxPushBatch != 500 ||
		info.Limits.MaxPullBatch != v1.MaxPull || info.Limits.MaxFileSize != v1.MaxFileSize {
		t.Fatalf("info: %+v", info)
	}

	closed := newEnvWith(t, func(s *Server) { s.DisableCreate = true })
	closed.call("GET", "/api/v1/info", "", nil, &info)
	if strings.Contains(strings.Join(info.Capabilities, ","), "create") {
		t.Fatalf("create is advertised on a closed server: %v", info.Capabilities)
	}
	var fail v1Error
	if c := closed.call("POST", "/api/v1/workspaces", "", map[string]string{"name": "a", "nickname": "b", "device": "c"}, &fail); c != 403 || fail.Error != "forbidden" {
		t.Fatalf("create on a closed server: %d %+v", c, fail)
	}
}

func TestV1JoinAndWorkspace(t *testing.T) {
	var store v1.Store
	e := newEnvWith(t, func(s *Server) { store = s.V1 })
	var leader, member v1JoinedResp
	if c := e.call("POST", "/api/v1/workspaces", "", map[string]string{"name": " 2026 秋季填图 ", "nickname": "张三", "device": "Galaxy S24"}, &leader); c != 201 {
		t.Fatalf("create: %d", c)
	}
	if leader.Token == "" || leader.Workspace.InviteCode == "" || leader.Workspace.Leader != leader.Member.ID ||
		leader.Workspace.Name != "2026 秋季填图" || leader.Member.Roles == nil {
		t.Fatalf("create: %+v", leader)
	}
	ws := "/api/v1/workspaces/" + leader.Workspace.ID

	if c := e.call("POST", "/api/v1/join", "", map[string]string{"invite_code": strings.ToLower(leader.Workspace.InviteCode), "nickname": "李四", "device": "Mate 60"}, &member); c != 200 {
		t.Fatalf("join: %d", c)
	}
	if member.Member.ID == leader.Member.ID || member.Workspace.InviteCode != "" || len(member.Member.Roles) != 0 {
		t.Fatalf("a new member sees no invite code and has only the base role: %+v", member)
	}

	// Signing in.
	var fail v1Error
	if c := e.call("GET", ws, "", nil, &fail); c != 401 || fail.Error != "unauthorized" {
		t.Fatalf("no token: %d %+v", c, fail)
	}
	var other v1JoinedResp
	e.call("POST", "/api/v1/workspaces", "", map[string]string{"name": "别组", "nickname": "王五", "device": "Pixel"}, &other)
	if c := e.call("GET", ws, other.Token, nil, nil); c != 401 {
		t.Fatalf("another workspace's token: %d", c)
	}
	var got v1JoinedResp
	if c := e.call("GET", ws, member.Token, nil, &got.Workspace); c != 200 || got.Workspace.ID != leader.Workspace.ID || got.Workspace.InviteCode != "" {
		t.Fatalf("workspace for a member: %d %+v", c, got.Workspace)
	}

	// Only those with manage_workspace change it; only those with manage_members reset the invite.
	if c := e.call("PATCH", ws, member.Token, map[string]any{"name": "改"}, &fail); c != 403 || fail.Error != "forbidden" || fail.Permission != "manage_workspace" {
		t.Fatalf("member renames: %d %+v", c, fail)
	}
	if c := e.call("PATCH", ws, leader.Token, map[string]any{"name": "", "archived": true}, nil); c != 400 {
		t.Fatalf("empty name: %d", c)
	}
	if c := e.call("PATCH", ws, leader.Token, map[string]any{"name": "秋季填图", "archived": true}, &got.Workspace); c != 200 ||
		got.Workspace.Name != "秋季填图" || got.Workspace.ArchivedAt == nil {
		t.Fatalf("rename and archive: %d %+v", c, got.Workspace)
	}
	if c := e.call("POST", ws+"/invite/reset", member.Token, nil, &fail); c != 403 || fail.Permission != "manage_members" {
		t.Fatalf("member resets the invite: %d", c)
	}
	e.call("PATCH", ws, leader.Token, map[string]any{"archived": false}, nil)
	var reset struct {
		InviteCode string `json:"invite_code"`
	}
	if c := e.call("POST", ws+"/invite/reset", leader.Token, nil, &reset); c != 200 || reset.InviteCode == "" || reset.InviteCode == leader.Workspace.InviteCode {
		t.Fatalf("reset: %d %+v", c, reset)
	}
	if c := e.call("POST", "/api/v1/join", "", map[string]string{"invite_code": leader.Workspace.InviteCode, "nickname": "赵六", "device": "x"}, &fail); c != 404 || fail.Error != "bad_invite" {
		t.Fatalf("old invite: %d %+v", c, fail)
	}

	// A member with manage_members sees the invite code.
	role, err := store.CreateRole(context.Background(), leader.Workspace.ID, leader.Member.ID, "副组长", 10, map[string]perm.Level{perm.ManageMembers: perm.All})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.SetMemberRoles(context.Background(), leader.Workspace.ID, leader.Member.ID, member.Member.ID, []string{role.ID}); err != nil {
		t.Fatal(err)
	}
	if e.call("GET", ws, member.Token, nil, &got.Workspace); got.Workspace.InviteCode != reset.InviteCode || got.Workspace.AccessVersion == 0 {
		t.Fatalf("workspace for a member who manages members: %+v", got.Workspace)
	}

	// A transfer code signs a new device in as the same member.
	code, _, err := store.CreateTransferCode(context.Background(), leader.Workspace.ID, member.Member.ID, member.Member.ID)
	if err != nil {
		t.Fatal(err)
	}
	var moved v1JoinedResp
	if c := e.call("POST", "/api/v1/join", "", map[string]string{"transfer_code": code, "device": "新手机"}, &moved); c != 200 ||
		moved.Member.ID != member.Member.ID || moved.Token == member.Token {
		t.Fatalf("transfer: %d %+v", c, moved)
	}
	for _, body := range []map[string]string{
		{"device": "x"},
		{"invite_code": reset.InviteCode, "transfer_code": code, "nickname": "a", "device": "x"},
		{"invite_code": reset.InviteCode, "device": "x"},
	} {
		if c := e.call("POST", "/api/v1/join", "", body, nil); c != 400 {
			t.Fatalf("join %v: %d", body, c)
		}
	}
}

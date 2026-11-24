package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/realtime"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
)

type env struct {
	t   *testing.T
	srv *httptest.Server
}

func newEnv(t *testing.T) *env {
	t.Helper()
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	hub := realtime.NewHub()
	hub.MinPositionInterval = 0
	s := &Server{
		Name: "test", Version: "test", Store: db, Hub: hub,
		Files: &blob.Disk{Root: t.TempDir(), MaxSize: 1 << 20},
		Log:   slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	srv := httptest.NewServer(s.Handler())
	t.Cleanup(srv.Close)
	return &env{t, srv}
}

// call sends a request and decodes a JSON response into out (if non-nil).
func (e *env) call(method, path, token string, body any, out any) int {
	e.t.Helper()
	var r io.Reader
	switch b := body.(type) {
	case nil:
	case []byte:
		r = bytes.NewReader(b)
	default:
		buf, _ := json.Marshal(b)
		r = bytes.NewReader(buf)
	}
	req, _ := http.NewRequest(method, e.srv.URL+path, r)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		data, _ := io.ReadAll(resp.Body)
		if err := json.Unmarshal(data, out); err != nil {
			e.t.Fatalf("%s %s: decode %q: %v", method, path, data, err)
		}
	}
	return resp.StatusCode
}

func feature(id string, version int64, at string) map[string]any {
	return map[string]any{
		"type": "Feature", "id": id,
		"geometry":   map[string]any{"type": "Point", "coordinates": []float64{116.39, 39.91, 52.3}},
		"properties": map[string]any{"kind": "geo_point", "title": "D001", "strike": 35},
		"sync":       map[string]any{"version": version, "updated_at": at},
	}
}

func TestTeamFlow(t *testing.T) {
	e := newEnv(t)

	var info infoResp
	if e.call("GET", "/api/v0/info", "", nil, &info) != 200 || info.Protocol != "0" || info.MaxUploadBytes != 1<<20 {
		t.Fatalf("info: %+v", info)
	}

	var leader joinResp
	if c := e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "野外一组", "nickname": "组长"}, &leader); c != 201 {
		t.Fatalf("create: %d", c)
	}
	base := "/api/v0/workspaces/" + leader.WorkspaceID

	var member joinResp
	if c := e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": strings.ToLower(leader.InviteCode), "nickname": "组员A"}, &member); c != 200 {
		t.Fatalf("join: %d", c)
	}
	if member.Role != "member" || member.InviteCode != "" {
		t.Fatalf("join resp: %+v", member)
	}

	var wsResp map[string]any
	e.call("GET", base, member.Token, nil, &wsResp)
	if _, ok := wsResp["invite_code"]; ok {
		t.Fatal("member must not see invite code")
	}
	e.call("GET", base, leader.Token, nil, &wsResp)
	if wsResp["invite_code"] != leader.InviteCode {
		t.Fatalf("leader workspace view: %v", wsResp)
	}

	if c := e.call("GET", base+"/members", "", nil, nil); c != 401 {
		t.Fatalf("no token: %d", c)
	}
	if c := e.call("POST", base+"/invite", member.Token, nil, nil); c != 403 {
		t.Fatalf("member reset invite: %d", c)
	}

	// Push and pull.
	var push pushResp
	c := e.call("POST", base+"/features", member.Token, map[string]any{"features": []any{
		feature("f1", 1, "2026-11-24T08:00:00Z"), feature("f2", 1, "2026-11-24T08:00:00Z"),
	}}, &push)
	if c != 200 || push.Seq != 2 || push.Results[0].Status != "applied" {
		t.Fatalf("push: %d %+v", c, push)
	}
	bad := feature("f3", 0, "2026-11-24T08:00:00Z")
	if c := e.call("POST", base+"/features", member.Token, map[string]any{"features": []any{bad}}, nil); c != 400 {
		t.Fatalf("invalid feature: %d", c)
	}
	e.call("POST", base+"/features", leader.Token, map[string]any{"features": []any{
		feature("f1", 1, "2026-11-24T07:00:00Z"),
	}}, &push)
	if push.Results[0].Status != "stale" || push.Results[0].Current == nil || push.Results[0].Current.Sync.UpdatedBy != member.MemberID {
		t.Fatalf("stale push: %+v", push)
	}

	var pull pullResp
	e.call("GET", base+"/features?since=0&limit=1", leader.Token, nil, &pull)
	if len(pull.Features) != 1 || !pull.More || pull.Seq != 1 {
		t.Fatalf("pull page: %+v", pull)
	}
	e.call("GET", base+"/features?since=1", leader.Token, nil, &pull)
	if len(pull.Features) != 1 || pull.More || pull.Seq != 2 || pull.Features[0].ID != "f2" {
		t.Fatalf("pull rest: %+v", pull)
	}
	var props map[string]any
	_ = json.Unmarshal(pull.Features[0].Properties, &props)
	if props["title"] != "D001" {
		t.Fatalf("properties lost: %s", pull.Features[0].Properties)
	}

	// Read-only members cannot push.
	if c := e.call("PATCH", base+"/members/"+member.MemberID, leader.Token, map[string]string{"role": "readonly"}, nil); c != 204 {
		t.Fatalf("set role: %d", c)
	}
	if c := e.call("POST", base+"/features", member.Token, map[string]any{"features": []any{}}, nil); c != 403 {
		t.Fatalf("readonly push: %d", c)
	}
	if c := e.call("DELETE", base+"/members/"+leader.MemberID, leader.Token, nil, nil); c != 409 {
		t.Fatalf("revoke last leader: %d", c)
	}
	if c := e.call("DELETE", base+"/members/"+member.MemberID, leader.Token, nil, nil); c != 204 {
		t.Fatalf("revoke: %d", c)
	}
	if c := e.call("GET", base+"/members", member.Token, nil, nil); c != 401 {
		t.Fatalf("revoked token: %d", c)
	}
}

func TestFiles(t *testing.T) {
	e := newEnv(t)
	var leader joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "w", "nickname": "a"}, &leader)
	base := "/api/v0/workspaces/" + leader.WorkspaceID + "/files/"
	body := []byte("fake jpeg bytes")
	h := sha256.Sum256(body)
	sha := hex.EncodeToString(h[:])

	if c := e.call("PUT", base+sha, leader.Token, body, nil); c != 201 {
		t.Fatalf("put: %d", c)
	}
	if c := e.call("PUT", base+sha, leader.Token, body, nil); c != 204 {
		t.Fatalf("put again: %d", c)
	}
	if c := e.call("PUT", base+strings.Repeat("0", 64), leader.Token, body, nil); c != 400 {
		t.Fatalf("mismatch: %d", c)
	}
	if c := e.call("HEAD", base+sha, leader.Token, nil, nil); c != 200 {
		t.Fatalf("head: %d", c)
	}
	req, _ := http.NewRequest("GET", e.srv.URL+base+sha, nil)
	req.Header.Set("Authorization", "Bearer "+leader.Token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if !bytes.Equal(got, body) || resp.Header.Get("ETag") == "" {
		t.Fatalf("get: %q %v", got, resp.Header)
	}
	if c := e.call("GET", base+strings.Repeat("a", 64), leader.Token, nil, nil); c != 404 {
		t.Fatalf("missing: %d", c)
	}
}

func readMsg(t *testing.T, ctx context.Context, c *websocket.Conn) map[string]any {
	t.Helper()
	_, data, err := c.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	_ = json.Unmarshal(data, &m)
	return m
}

func TestRealtime(t *testing.T) {
	e := newEnv(t)
	var a, b joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "w", "nickname": "a"}, &a)
	e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": a.InviteCode, "nickname": "b"}, &b)
	wsURL := "ws" + strings.TrimPrefix(e.srv.URL, "http") + "/api/v0/workspaces/" + a.WorkspaceID + "/ws"

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if _, _, err := websocket.Dial(ctx, wsURL+"?access_token=wrong", nil); err == nil {
		t.Fatal("dial with bad token succeeded")
	}

	ca, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + a.Token}}})
	if err != nil {
		t.Fatal(err)
	}
	defer ca.CloseNow()
	if m := readMsg(t, ctx, ca); m["type"] != "hello" {
		t.Fatalf("hello a: %v", m)
	}

	cb, _, err := websocket.Dial(ctx, wsURL+"?access_token="+b.Token, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer cb.CloseNow()
	readMsg(t, ctx, cb) // hello
	if m := readMsg(t, ctx, ca); m["type"] != "presence" || m["member_id"] != b.MemberID {
		t.Fatalf("presence: %v", m)
	}

	_ = cb.Write(ctx, websocket.MessageText, []byte(`{"type":"position","lon":116.4,"lat":39.9,"accuracy":4.5}`))
	if m := readMsg(t, ctx, ca); m["type"] != "position" || m["member_id"] != b.MemberID || m["nickname"] != "b" {
		t.Fatalf("position relay: %v", m)
	}

	_ = ca.Write(ctx, websocket.MessageText, []byte(`{"type":"ping"}`))
	if m := readMsg(t, ctx, ca); m["type"] != "pong" {
		t.Fatalf("pong: %v", m)
	}

	e.call("POST", "/api/v0/workspaces/"+a.WorkspaceID+"/features", a.Token,
		map[string]any{"features": []any{feature("f1", 1, "2026-11-24T08:00:00Z")}}, nil)
	if m := readMsg(t, ctx, cb); m["type"] != "feature_changed" || m["seq"] != float64(1) {
		t.Fatalf("feature_changed: %v", m)
	}

	// Revoking b closes b's socket.
	e.call("DELETE", "/api/v0/workspaces/"+a.WorkspaceID+"/members/"+b.MemberID, a.Token, nil, nil)
	for {
		if _, _, err := cb.Read(ctx); err != nil {
			break
		}
	}
}

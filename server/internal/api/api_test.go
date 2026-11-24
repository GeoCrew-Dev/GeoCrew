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
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/push"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/realtime"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store/sqlite"
)

type env struct {
	t   *testing.T
	srv *httptest.Server
}

func newEnv(t *testing.T) *env { return newEnvWith(t, nil) }

// newEnvWith lets a test adjust the server (for example attach a push sender) before it starts.
func newEnvWith(t *testing.T, adjust func(*Server)) *env {
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
	if adjust != nil {
		adjust(s)
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

func TestJoinIsRateLimited(t *testing.T) {
	e := newEnv(t)
	limited := false
	for i := 0; i < 15; i++ {
		c := e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": "WRONG000", "nickname": "x"}, nil)
		if c == http.StatusTooManyRequests {
			limited = true
			break
		}
		if c != http.StatusNotFound {
			t.Fatalf("attempt %d: %d", i, c)
		}
	}
	if !limited {
		t.Fatal("invite guessing was never limited")
	}
}

func TestCreateCanBeDisabled(t *testing.T) {
	db, err := sqlite.Open(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := &Server{Name: "t", Store: db, DisableCreate: true, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	srv := httptest.NewServer(s.Handler())
	defer srv.Close()
	e := &env{t, srv}
	var info infoResp
	e.call("GET", "/api/v0/info", "", nil, &info)
	for _, c := range info.Capabilities {
		if c == "create" {
			t.Fatal("info still advertises create")
		}
	}
	if c := e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "a", "nickname": "b"}, nil); c != http.StatusForbidden {
		t.Fatalf("create: %d", c)
	}
}

func TestSettings(t *testing.T) {
	e := newEnv(t)
	var leader, member joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "一组", "nickname": "组长"}, &leader)
	e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": leader.InviteCode, "nickname": "A"}, &member)
	base := "/api/v0/workspaces/" + leader.WorkspaceID

	var got settingsResp
	if c := e.call("GET", base+"/settings", member.Token, nil, &got); c != 200 || got.Version != 0 || string(got.Settings) != "{}" {
		t.Fatalf("initial: %d %+v", c, got)
	}
	form := map[string]any{"form": []any{map[string]any{"key": "formation", "label": "地层"}}}
	if c := e.call("PUT", base+"/settings", member.Token, map[string]any{"settings": form}, nil); c != 403 {
		t.Fatalf("member put: %d", c)
	}
	if c := e.call("PUT", base+"/settings", leader.Token, map[string]any{"settings": form, "base_version": 0}, &got); c != 200 || got.Version != 1 {
		t.Fatalf("put: %d %+v", c, got)
	}
	if c := e.call("PUT", base+"/settings", leader.Token, map[string]any{"settings": form, "base_version": 0}, nil); c != 409 {
		t.Fatalf("stale put: %d", c)
	}
	if c := e.call("PUT", base+"/settings", leader.Token, map[string]any{"settings": []int{1}}, nil); c != 400 {
		t.Fatalf("non-object: %d", c)
	}
	e.call("GET", base+"/settings", member.Token, nil, &got)
	var back map[string][]map[string]string
	if err := json.Unmarshal(got.Settings, &back); err != nil || got.Version != 1 || back["form"][0]["label"] != "地层" {
		t.Fatalf("get after put: %+v %v", got, err)
	}
	var wsResp map[string]any
	e.call("GET", base, member.Token, nil, &wsResp)
	if wsResp["settings_version"] != float64(1) {
		t.Fatalf("workspace settings_version: %v", wsResp)
	}
}

func TestExportGeoJSON(t *testing.T) {
	e := newEnv(t)
	var leader joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "一组", "nickname": "组长"}, &leader)
	base := "/api/v0/workspaces/" + leader.WorkspaceID
	e.call("POST", base+"/features", leader.Token, map[string]any{"features": []any{feature("f1", 1, "2026-11-24T08:00:00Z")}}, nil)
	var fc struct {
		Type     string           `json:"type"`
		Features []map[string]any `json:"features"`
	}
	if c := e.call("GET", base+"/export.geojson", leader.Token, nil, &fc); c != 200 || len(fc.Features) != 1 || fc.Features[0]["id"] != "f1" {
		t.Fatalf("export: %d %+v", c, fc)
	}
	if c := e.call("GET", base+"/export.geojson", "", nil, nil); c != 401 {
		t.Fatalf("export without token: %d", c)
	}

	req, _ := http.NewRequest("GET", e.srv.URL+base+"/export.geocrew.zip", nil)
	req.Header.Set("Authorization", "Bearer "+leader.Token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	data, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "application/zip" || !bytes.HasPrefix(data, []byte("PK")) {
		t.Fatalf("package: %d %q %d bytes", resp.StatusCode, resp.Header.Get("Content-Type"), len(data))
	}
}

func TestComments(t *testing.T) {
	e := newEnv(t)
	var leader, a, b joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "一组", "nickname": "组长"}, &leader)
	e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": leader.InviteCode, "nickname": "A"}, &a)
	e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": leader.InviteCode, "nickname": "B"}, &b)
	base := "/api/v0/workspaces/" + leader.WorkspaceID

	post := func(token string, body map[string]any) (int, store.Comment) {
		var c store.Comment
		code := e.call("POST", base+"/comments", token, body, &c)
		return code, c
	}
	c1 := map[string]any{"id": "c1", "feature_id": "f1", "body": "  这里的接触关系再看一下  "}
	if code, c := post(a.Token, c1); code != 200 || c.Seq != 1 || c.Nickname != "A" || c.Body != "这里的接触关系再看一下" || c.FeatureID != "f1" {
		t.Fatalf("add: %d %+v", code, c)
	}
	if code, c := post(a.Token, c1); code != 200 || c.Seq != 1 {
		t.Fatalf("retry should be idempotent: %d %+v", code, c)
	}
	if code, _ := post(b.Token, c1); code != 409 {
		t.Fatalf("id reuse by another member: %d", code)
	}
	if code, c := post(b.Token, map[string]any{"id": "c2", "body": "集合点在这", "at": []float64{116.4, 39.9}}); code != 200 || c.Seq != 2 || len(c.At) != 2 {
		t.Fatalf("general message: %d %+v", code, c)
	}
	for _, bad := range []map[string]any{
		{"id": "c3", "body": "   "},
		{"id": "bad id", "body": "x"},
		{"id": "c3", "body": "x", "at": []float64{200, 0}},
		{"id": "c3", "body": strings.Repeat("字", store.MaxCommentRunes+1)},
	} {
		if code, _ := post(a.Token, bad); code != 400 {
			t.Fatalf("invalid %v: %d", bad, code)
		}
	}

	if code := e.call("DELETE", base+"/comments/c2", a.Token, nil, nil); code != 403 {
		t.Fatalf("delete someone else's: %d", code)
	}
	if code := e.call("DELETE", base+"/comments/c2", leader.Token, nil, nil); code != 204 {
		t.Fatalf("leader delete: %d", code)
	}
	if code := e.call("DELETE", base+"/comments/nope", leader.Token, nil, nil); code != 404 {
		t.Fatalf("delete missing: %d", code)
	}

	var page commentsResp
	if code := e.call("GET", base+"/comments?since=1", b.Token, nil, &page); code != 200 || page.Seq != 3 || len(page.Comments) != 1 {
		t.Fatalf("pull: %d %+v", code, page)
	}
	if d := page.Comments[0]; d.ID != "c2" || !d.Deleted || d.Body != "" || d.At != nil {
		t.Fatalf("tombstone: %+v", d)
	}
	if code := e.call("GET", base+"/comments?limit=1", b.Token, nil, &page); code != 200 || !page.More || page.Seq != 1 {
		t.Fatalf("paging: %d %+v", code, page)
	}

	e.call("PATCH", base+"/members/"+b.MemberID, leader.Token, map[string]string{"role": "readonly"}, nil)
	if code, _ := post(b.Token, map[string]any{"id": "c4", "body": "x"}); code != 403 {
		t.Fatalf("readonly post: %d", code)
	}
	// ...but may still call for help.
	code, sos := post(b.Token, map[string]any{"id": "c5", "body": "需要帮忙", "urgent": true})
	if code != 200 || !sos.Urgent {
		t.Fatalf("readonly urgent: %d %+v", code, sos)
	}
	if code, _ := post(leader.Token, map[string]any{"id": "c6", "feature_id": "f1", "body": "x", "urgent": true}); code != 400 {
		t.Fatalf("urgent on a record: %d", code)
	}
	if code, again := post(b.Token, map[string]any{"id": "c5", "body": "需要帮忙", "urgent": true}); code != 200 || again.Seq != sos.Seq {
		t.Fatalf("urgent retry: %d %+v", code, again)
	}
}

func TestPushNotices(t *testing.T) {
	var mu sync.Mutex
	got := map[string][]push.Notice{}
	sink := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var n push.Notice
		_ = json.NewDecoder(r.Body).Decode(&n)
		mu.Lock()
		got[r.URL.Path] = append(got[r.URL.Path], n)
		mu.Unlock()
	}))
	defer sink.Close()
	var sender *push.Sender
	e := newEnvWith(t, func(s *Server) {
		sender = push.New(s.Store, push.Options{AllowPrivate: true, Log: s.Log})
		s.Push = sender
	})

	var info infoResp
	e.call("GET", "/api/v0/info", "", nil, &info)
	if !slices.Contains(info.Capabilities, "push") {
		t.Fatalf("push capability missing: %v", info.Capabilities)
	}
	var leader, member joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "组", "nickname": "组长"}, &leader)
	e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": leader.InviteCode, "nickname": "组员"}, &member)
	base := "/api/v0/workspaces/" + leader.WorkspaceID

	if c := e.call("GET", base+"/push", member.Token, nil, nil); c != 404 {
		t.Fatalf("no target yet: %d", c)
	}
	if c := e.call("PUT", base+"/push", member.Token, map[string]any{"endpoint": "ftp://x"}, nil); c != 400 {
		t.Fatalf("bad endpoint: %d", c)
	}
	if c := e.call("PUT", base+"/push", member.Token, map[string]any{"endpoint": sink.URL + "/m", "events": []string{"bogus"}}, nil); c != 400 {
		t.Fatalf("bad events: %d", c)
	}
	var tgt store.PushTarget
	if c := e.call("PUT", base+"/push", member.Token, map[string]any{"endpoint": sink.URL + "/m"}, &tgt); c != 200 || len(tgt.Events) != len(push.Events) {
		t.Fatalf("register: %d %+v", c, tgt)
	}
	e.call("PUT", base+"/push", leader.Token, map[string]any{"endpoint": sink.URL + "/l", "events": []string{"task"}}, nil)

	// A message from the leader reaches the member only.
	e.call("POST", base+"/comments", leader.Token, map[string]any{"id": "c1", "body": "收队"}, nil)
	// A record assigned to the member by the leader.
	f := feature("f1", 1, "2026-11-24T08:00:00Z")
	f["properties"].(map[string]any)["assignee"] = member.MemberID
	e.call("POST", base+"/features", leader.Token, map[string]any{"features": []any{f}}, nil)
	// Editing it again without changing the assignee is not a new task.
	f2 := feature("f1", 2, "2026-11-24T08:05:00Z")
	f2["properties"].(map[string]any)["assignee"] = member.MemberID
	e.call("POST", base+"/features", leader.Token, map[string]any{"features": []any{f2}}, nil)
	// The member assigning a record to themselves notifies nobody.
	f3 := feature("f2", 1, "2026-11-24T08:06:00Z")
	f3["properties"].(map[string]any)["assignee"] = member.MemberID
	e.call("POST", base+"/features", member.Token, map[string]any{"features": []any{f3}}, nil)
	// An urgent message reaches the leader although they only follow tasks.
	e.call("POST", base+"/comments", member.Token, map[string]any{"id": "sos", "body": "崴脚了", "at": []float64{116.1, 40.1}, "urgent": true}, nil)
	sender.Close()

	if l := got["/l"]; len(l) != 1 || l[0].Type != "alert" || l[0].From != member.MemberID {
		t.Fatalf("leader got %v", l)
	}
	m := got["/m"]
	// Workers deliver in parallel, so order is not guaranteed.
	slices.SortFunc(m, func(a, b push.Notice) int { return strings.Compare(a.Type, b.Type) })
	if len(m) != 2 || m[0].Type != "message" || m[0].From != leader.MemberID || m[1].Type != "task" || m[1].Feature != "f1" {
		t.Fatalf("member notices: %+v", m)
	}

	if c := e.call("DELETE", base+"/push", member.Token, nil, nil); c != 204 {
		t.Fatalf("delete: %d", c)
	}
	if c := e.call("GET", base+"/push", member.Token, nil, nil); c != 404 {
		t.Fatalf("after delete: %d", c)
	}
}

func TestRollCall(t *testing.T) {
	var mu sync.Mutex
	got := map[string][]push.Notice{}
	sink := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var n push.Notice
		_ = json.NewDecoder(r.Body).Decode(&n)
		mu.Lock()
		got[r.URL.Path] = append(got[r.URL.Path], n)
		mu.Unlock()
	}))
	defer sink.Close()
	var sender *push.Sender
	e := newEnvWith(t, func(s *Server) {
		sender = push.New(s.Store, push.Options{AllowPrivate: true, Log: s.Log})
		s.Push = sender
	})
	var leader, a, b joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "组", "nickname": "组长"}, &leader)
	e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": leader.InviteCode, "nickname": "A"}, &a)
	e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": leader.InviteCode, "nickname": "B"}, &b)
	base := "/api/v0/workspaces/" + leader.WorkspaceID
	var info infoResp
	e.call("GET", "/api/v0/info", "", nil, &info)
	if !slices.Contains(info.Capabilities, "rollcall") {
		t.Fatalf("rollcall capability missing: %v", info.Capabilities)
	}
	// Everyone follows tasks only; roll calls get through regardless.
	for _, m := range []joinResp{leader, a, b} {
		e.call("PUT", base+"/push", m.Token, map[string]any{"endpoint": sink.URL + "/" + m.MemberID, "events": []string{"task"}}, nil)
	}
	post := func(token string, body map[string]any) (int, store.Comment) {
		var c store.Comment
		code := e.call("POST", base+"/comments", token, body, &c)
		return code, c
	}

	if code, _ := post(a.Token, map[string]any{"id": "r0", "body": "点名", "roll_call": true}); code != 403 {
		t.Fatalf("member calls the roll: %d", code)
	}
	code, call := post(leader.Token, map[string]any{"id": "r1", "body": "18 点前回营地报平安", "roll_call": true})
	if code != 200 || !call.RollCall {
		t.Fatalf("roll call: %d %+v", code, call)
	}
	for _, bad := range []map[string]any{
		{"id": "x1", "body": "x", "roll_call": true, "feature_id": "f1"},
		{"id": "x2", "body": "x", "answer_to": "r1", "feature_id": "f1"},
		{"id": "x3", "body": "x", "answer_to": "r1", "roll_call": true},
		{"id": "x4", "body": "平安", "answer_to": "nope"},
		{"id": "x5", "body": "平安", "answer_to": "bad id"},
	} {
		if code, _ := post(leader.Token, bad); code != 400 {
			t.Fatalf("invalid %v: %d", bad, code)
		}
	}
	if code, _ := post(a.Token, map[string]any{"id": "m1", "body": "普通消息"}); code != 200 {
		t.Fatalf("plain message: %d", code)
	}
	if code, _ := post(a.Token, map[string]any{"id": "x6", "body": "平安", "answer_to": "m1"}); code != 400 {
		t.Fatalf("answer to a plain message: %d", code)
	}
	// A read-only member may still report in.
	e.call("PATCH", base+"/members/"+b.MemberID, leader.Token, map[string]string{"role": "readonly"}, nil)
	code, ans := post(b.Token, map[string]any{"id": "a-b", "body": "平安", "answer_to": "r1", "at": []float64{116.2, 40.0}})
	if code != 200 || ans.AnswerTo != "r1" || len(ans.At) != 2 {
		t.Fatalf("answer: %d %+v", code, ans)
	}
	if code, again := post(b.Token, map[string]any{"id": "a-b", "body": "平安", "answer_to": "r1", "at": []float64{116.2, 40.0}}); code != 200 || again.Seq != ans.Seq {
		t.Fatalf("answer retry: %d %+v", code, again)
	}
	sender.Close()

	// The retried answer may notify twice (protocol §9); both are B's answer.
	if l := got["/"+leader.MemberID]; len(l) == 0 || slices.ContainsFunc(l, func(n push.Notice) bool { return n.Type != "rollcall" || n.From != b.MemberID }) {
		t.Fatalf("leader got %+v", l)
	}
	if l := got["/"+a.MemberID]; len(l) != 1 || l[0].Type != "rollcall" || l[0].From != leader.MemberID {
		t.Fatalf("member A got %+v", l)
	}
	if l := got["/"+b.MemberID]; len(l) != 1 || l[0].Type != "rollcall" {
		t.Fatalf("member B got %+v", l)
	}
	// Deleting the roll call clears its flag; answers to it are refused from then on.
	e.call("DELETE", base+"/comments/r1", leader.Token, nil, nil)
	if code, _ := post(a.Token, map[string]any{"id": "a-a", "body": "平安", "answer_to": "r1"}); code != 400 {
		t.Fatalf("answer to a deleted roll call: %d", code)
	}
}

func TestPushDisabled(t *testing.T) {
	e := newEnv(t)
	var leader joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "组", "nickname": "组长"}, &leader)
	var body map[string]any
	if c := e.call("PUT", "/api/v0/workspaces/"+leader.WorkspaceID+"/push", leader.Token, map[string]any{"endpoint": "https://ntfy.sh/x"}, &body); c != 404 || body["error"] != "push_disabled" {
		t.Fatalf("disabled: %d %v", c, body)
	}
}

func TestPushIsEncryptedWhenKeysAreGiven(t *testing.T) {
	type got struct {
		encoding string
		body     []byte
	}
	ch := make(chan got, 4)
	sink := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		ch <- got{r.Header.Get("Content-Encoding"), b}
	}))
	defer sink.Close()
	var sender *push.Sender
	e := newEnvWith(t, func(s *Server) {
		sender = push.New(s.Store, push.Options{AllowPrivate: true, Log: s.Log})
		s.Push = sender
	})
	var leader, member joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "组", "nickname": "组长"}, &leader)
	e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": leader.InviteCode, "nickname": "组员"}, &member)
	base := "/api/v0/workspaces/" + leader.WorkspaceID
	keys := map[string]string{
		"p256dh": "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
		"auth":   "BTBZMqHH6r4Tts7J_aSIgg",
	}
	if c := e.call("PUT", base+"/push", member.Token, map[string]any{"endpoint": sink.URL, "keys": map[string]string{"p256dh": "x", "auth": "y"}}, nil); c != 400 {
		t.Fatalf("bad keys: %d", c)
	}
	var tgt store.PushTarget
	if c := e.call("PUT", base+"/push", member.Token, map[string]any{"endpoint": sink.URL, "keys": keys}, &tgt); c != 200 || tgt.Keys == nil {
		t.Fatalf("register: %d %+v", c, tgt)
	}
	e.call("POST", base+"/comments", leader.Token, map[string]any{"id": "c1", "body": "收队"}, nil)
	sender.Close()
	g := <-ch
	if g.encoding != "aes128gcm" || bytes.Contains(g.body, []byte("message")) || len(g.body) < 16+4+1+65+16 {
		t.Fatalf("not encrypted: %q %q", g.encoding, g.body)
	}
}

func TestLastSeen(t *testing.T) {
	e := newEnv(t)
	var leader, member joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "组", "nickname": "组长"}, &leader)
	e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": leader.InviteCode, "nickname": "组员"}, &member)
	base := "/api/v0/workspaces/" + leader.WorkspaceID

	var list []map[string]any
	if c := e.call("GET", base+"/members", leader.Token, nil, &list); c != 200 {
		t.Fatalf("members: %d", c)
	}
	seen := map[string]any{}
	for _, m := range list {
		seen[m["nickname"].(string)] = m["last_seen"]
	}
	// The leader's own request counts; the member has not made one yet.
	if s, ok := seen["组长"].(string); !ok || s == "" {
		t.Fatalf("leader last_seen: %v", list)
	}
	if v := seen["组员"]; v != nil {
		t.Fatalf("member has not been seen yet: %v", v)
	}
	if ts, err := time.Parse(time.RFC3339, seen["组长"].(string)); err != nil || time.Since(ts) > 2*time.Minute || ts.Second() != 0 {
		t.Fatalf("last_seen %v %v", ts, err)
	}
	e.call("GET", base, member.Token, nil, nil)
	e.call("GET", base+"/members", leader.Token, nil, &list)
	for _, m := range list {
		if m["nickname"] == "组员" && m["last_seen"] == nil {
			t.Fatalf("member seen after a request: %v", list)
		}
	}
}

func TestViewLinks(t *testing.T) {
	e := newEnv(t)
	var a joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "w", "nickname": "a"}, &a)
	base := "/api/v0/workspaces/" + a.WorkspaceID
	e.call("POST", base+"/features", a.Token, map[string]any{"features": []any{feature("f1", 1, "2026-11-24T08:00:00Z")}}, nil)

	if c := e.call("GET", base+"/export.samples.csv?tz_offset=9999", a.Token, nil, nil); c != 400 {
		t.Fatalf("bad tz_offset: %d", c)
	}
	if c := e.call("POST", base+"/view-links", a.Token, map[string]int{"hours": 500}, nil); c != 400 {
		t.Fatalf("bad hours: %d", c)
	}
	var link viewLinkResp
	if c := e.call("POST", base+"/view-links", a.Token, nil, &link); c != 201 || !strings.HasPrefix(link.Token, "v_") ||
		!strings.Contains(link.Path, "#ws="+a.WorkspaceID+"&t="+link.Token) {
		t.Fatalf("create: %d %+v", c, link)
	}
	if d := time.Until(link.ExpiresAt); d < 23*time.Hour || d > 25*time.Hour {
		t.Fatalf("default expiry: %v", link.ExpiresAt)
	}

	// The link reads everything a member can read...
	var page map[string]any
	if c := e.call("GET", base+"/features", link.Token, nil, &page); c != 200 {
		t.Fatalf("viewer pull: %d", c)
	}
	for _, p := range []string{base, base + "/members", base + "/comments", base + "/export.geojson", base + "/export.samples.csv?tz_offset=480", base + "/export.sections.csv", base + "/export.kml", base + "/export.gpkg", base + "/export.routes.csv", base + "/checks"} {
		if c := e.call("GET", p, link.Token, nil, nil); c != 200 {
			t.Fatalf("viewer GET %s: %d", p, c)
		}
	}
	// ...and writes nothing, not even an urgent message.
	for _, w := range []struct{ method, path string }{
		{"POST", base + "/features"}, {"POST", base + "/comments"}, {"GET", base + "/push"},
		{"POST", base + "/view-links"}, {"POST", base + "/leave"},
	} {
		if c := e.call(w.method, w.path, link.Token, map[string]any{}, nil); c != 403 && c != 404 && c != 405 {
			t.Fatalf("viewer %s %s: %d", w.method, w.path, c)
		}
	}

	// A browser on the link does not make the member look online, and cannot send positions.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	wsURL := "ws" + strings.TrimPrefix(e.srv.URL, "http") + base + "/ws?access_token=" + link.Token
	cv, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer cv.CloseNow()
	if m := readMsg(t, ctx, cv); m["type"] != "hello" {
		t.Fatalf("viewer hello: %v", m)
	}
	var wsResp workspaceResp
	e.call("GET", base, a.Token, nil, &wsResp)
	if len(wsResp.Online) != 0 {
		t.Fatalf("viewer counted online: %v", wsResp.Online)
	}
	_ = cv.Write(ctx, websocket.MessageText, []byte(`{"type":"position","lon":116.4,"lat":39.9}`))
	if m := readMsg(t, ctx, cv); m["type"] != "error" {
		t.Fatalf("viewer position: %v", m)
	}

	resp, err := http.Get(e.srv.URL + "/view/")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || !strings.Contains(string(body), "maplibre") || resp.Header.Get("Content-Security-Policy") == "" {
		t.Fatalf("view page: %d %q", resp.StatusCode, resp.Header)
	}

	var rev struct{ Revoked int }
	if c := e.call("DELETE", base+"/view-links", a.Token, nil, &rev); c != 200 || rev.Revoked != 1 {
		t.Fatalf("revoke: %d %+v", c, rev)
	}
	if c := e.call("GET", base+"/features", link.Token, nil, nil); c != 401 {
		t.Fatalf("after revoke: %d", c)
	}
}

func TestOnlyLeadersReview(t *testing.T) {
	e := newEnv(t)
	var leader, member joinResp
	e.call("POST", "/api/v0/workspaces", "", map[string]string{"name": "组", "nickname": "组长"}, &leader)
	e.call("POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": leader.InviteCode, "nickname": "组员"}, &member)
	base := "/api/v0/workspaces/" + leader.WorkspaceID

	f := feature("r1", 1, "2026-11-24T08:00:00Z")
	f["properties"].(map[string]any)["review"] = map[string]any{"state": "approved", "by": member.MemberID}
	e.call("POST", base+"/features", member.Token, map[string]any{"features": []any{f}}, nil)

	f = feature("r1", 2, "2026-11-24T08:05:00Z")
	f["properties"].(map[string]any)["review"] = map[string]any{"state": "approved", "by": leader.MemberID, "at": "2026-11-24T08:05:00Z"}
	e.call("POST", base+"/features", leader.Token, map[string]any{"features": []any{f}}, nil)

	f = feature("r1", 3, "2026-11-24T08:10:00Z")
	f["properties"].(map[string]any)["review"] = map[string]any{"state": "redo"}
	if c := e.call("POST", base+"/features", member.Token, map[string]any{"features": []any{f}}, nil); c != 200 {
		t.Fatalf("member push: %d", c)
	}
	var pull pullResp
	e.call("GET", base+"/features?since=0", leader.Token, nil, &pull)
	if len(pull.Features) != 1 {
		t.Fatalf("pull: %+v", pull)
	}
	var p map[string]any
	_ = json.Unmarshal(pull.Features[0].Properties, &p)
	r, _ := p["review"].(map[string]any)
	if pull.Features[0].Sync.Version != 3 || r["state"] != "approved" || r["by"] != leader.MemberID {
		t.Fatalf("review should stay the leader's: %s", pull.Features[0].Properties)
	}
}

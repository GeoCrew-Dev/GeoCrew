package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func do(t *testing.T, h http.Handler, method, path, token string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&buf).Encode(body)
	}
	req := httptest.NewRequest(method, path, &buf)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestCreateJoinMembers(t *testing.T) {
	h := (&Server{Name: "test", Store: NewStore()}).Handler()

	rec := do(t, h, "POST", "/api/v0/workspaces", "", map[string]string{"name": "野外一组", "nickname": "组长"})
	if rec.Code != http.StatusCreated {
		t.Fatalf("create: %d %s", rec.Code, rec.Body)
	}
	var created joinResp
	_ = json.Unmarshal(rec.Body.Bytes(), &created)

	rec = do(t, h, "POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": created.InviteCode, "nickname": "组员A"})
	if rec.Code != http.StatusOK {
		t.Fatalf("join: %d %s", rec.Code, rec.Body)
	}

	rec = do(t, h, "GET", "/api/v0/workspaces/"+created.WorkspaceID+"/members", created.Token, nil)
	var ms []Member
	_ = json.Unmarshal(rec.Body.Bytes(), &ms)
	if rec.Code != http.StatusOK || len(ms) != 2 {
		t.Fatalf("members: %d %s", rec.Code, rec.Body)
	}

	if rec := do(t, h, "POST", "/api/v0/workspaces/join", "", map[string]string{"invite_code": "bad", "nickname": "x"}); rec.Code != http.StatusNotFound {
		t.Fatalf("bad invite: %d", rec.Code)
	}
	if rec := do(t, h, "GET", "/api/v0/workspaces/"+created.WorkspaceID+"/members", "wrong", nil); rec.Code != http.StatusNotFound {
		t.Fatalf("wrong token: %d", rec.Code)
	}
}

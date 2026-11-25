package console

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/update"
)

func releaseFeed(t *testing.T, version string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, `{"tag_name":"v%s","body":"- 新增归档\n- 修复 <b>同步</b>","published_at":"2026-11-25T03:00:00Z","assets":[]}`, version)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func do(h http.Handler, method, target string, form url.Values, token string, hdr map[string]string) *httptest.ResponseRecorder {
	var body *strings.Reader
	if form != nil {
		body = strings.NewReader(form.Encode())
	} else {
		body = strings.NewReader("")
	}
	r := httptest.NewRequest(method, target, body)
	if form != nil {
		r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	if token != "" {
		r.AddCookie(&http.Cookie{Name: cookieName, Value: token})
	}
	for k, v := range hdr {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestConsoleUpdate(t *testing.T) {
	feed := releaseFeed(t, "1.2.0")
	var applied, restarted atomic.Int32
	h := Handler(Options{
		Version: "1.1.0", Engine: "sqlite", DataDir: t.TempDir(), Token: "secret-token",
		Checker: &update.Checker{URL: feed.URL},
		Apply: func(ctx context.Context, r update.Release) (update.State, error) {
			applied.Add(1)
			return update.State{From: "1.1.0", To: r.Version, Status: update.StatusTrial}, nil
		},
		Restart: func() { restarted.Add(1) },
	})

	if w := do(h, "GET", "/", nil, "", nil); !strings.Contains(w.Body.String(), "管理令牌") || strings.Contains(w.Body.String(), "1.2.0") {
		t.Fatal("without the token only the login form shows")
	}
	if w := do(h, "GET", "/api/update", nil, "", nil); w.Code != http.StatusUnauthorized {
		t.Fatal(w.Code)
	}
	if w := do(h, "POST", "/login", url.Values{"token": {"wrong"}}, "", nil); w.Code != http.StatusUnauthorized {
		t.Fatal(w.Code)
	}
	w := do(h, "POST", "/login", url.Values{"token": {"secret-token"}}, "", nil)
	if w.Code != http.StatusSeeOther || !strings.Contains(w.Header().Get("Set-Cookie"), "SameSite=Strict") {
		t.Fatal(w.Code, w.Header())
	}

	w = do(h, "GET", "/", nil, "secret-token", nil)
	body := w.Body.String()
	if !strings.Contains(body, "有新版本 1.2.0") || !strings.Contains(body, "更新到 1.2.0") || !strings.Contains(body, "&lt;b&gt;同步") {
		t.Fatal(body)
	}

	if w := do(h, "POST", "/update", url.Values{"version": {"1.2.0"}}, "secret-token", map[string]string{"Sec-Fetch-Site": "cross-site"}); w.Code != http.StatusForbidden {
		t.Fatal("cross-site posts must be refused", w.Code)
	}
	if w := do(h, "POST", "/update", url.Values{"version": {"1.3.0"}}, "secret-token", nil); w.Code != http.StatusConflict || applied.Load() != 0 {
		t.Fatal("a version the operator did not see must not be installed", w.Code)
	}
	w = do(h, "POST", "/update", url.Values{"version": {"1.2.0"}}, "secret-token", map[string]string{"Sec-Fetch-Site": "same-origin"})
	if w.Code != http.StatusOK || applied.Load() != 1 || !strings.Contains(w.Body.String(), "正在重启") {
		t.Fatal(w.Code, w.Body.String())
	}

	w = do(h, "GET", "/api/update", nil, "", map[string]string{"Authorization": "Bearer secret-token"})
	if !strings.Contains(w.Body.String(), `"latest":"1.2.0"`) || !strings.Contains(w.Body.String(), `"newer":true`) {
		t.Fatal(w.Body.String())
	}
}

func TestConsoleDocker(t *testing.T) {
	feed := releaseFeed(t, "2.0.0")
	h := Handler(Options{Version: "1.0.0", Engine: "postgres", DataDir: t.TempDir(), Token: "t", Docker: true, Checker: &update.Checker{URL: feed.URL},
		Apply: func(context.Context, update.Release) (update.State, error) {
			t.Fatal("no in-place update in a container")
			return update.State{}, nil
		}})
	body := do(h, "GET", "/", nil, "t", nil).Body.String()
	if !strings.Contains(body, "docker compose pull") || strings.Contains(body, "更新到 2.0.0") {
		t.Fatal(body)
	}
	if w := do(h, "POST", "/update", url.Values{"version": {"2.0.0"}}, "t", nil); w.Code != http.StatusConflict {
		t.Fatal(w.Code)
	}
}

func TestConsoleDevBuild(t *testing.T) {
	feed := releaseFeed(t, "2.0.0")
	h := Handler(Options{Version: "dev", Engine: "sqlite", DataDir: t.TempDir(), Token: "t", Checker: &update.Checker{URL: feed.URL}})
	if body := do(h, "GET", "/", nil, "t", nil).Body.String(); strings.Contains(body, "有新版本") {
		t.Fatal("a development build is not offered releases")
	}
}

func TestNoTokenNoAccess(t *testing.T) {
	h := Handler(Options{Version: "1", Checker: &update.Checker{URL: "http://127.0.0.1:1"}})
	if w := do(h, "GET", "/api/update", nil, "", map[string]string{"Authorization": "Bearer "}); w.Code != http.StatusUnauthorized {
		t.Fatal(w.Code)
	}
}

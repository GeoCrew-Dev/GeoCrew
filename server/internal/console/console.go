// Package console is the operator's web page on a private listener: the
// running version, whether a newer release exists with its notes, and a
// button that installs it in place (or, in a container, the commands that
// pull the new image).
//
// It has full control over the program file, so it is off unless an
// address is configured, and every request needs the admin token.
package console

import (
	"context"
	"crypto/subtle"
	"embed"
	"encoding/json"
	"errors"
	"html/template"
	"log/slog"
	"net/http"
	"net/url"
	"runtime"
	"sync"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/update"
)

//go:embed page.html
var pages embed.FS

var page = template.Must(template.New("page.html").Funcs(template.FuncMap{
	"when": func(t time.Time) string {
		if t.IsZero() {
			return ""
		}
		return t.In(time.Local).Format("2006-01-02 15:04")
	},
}).ParseFS(pages, "page.html"))

// Options wires the console to the running server.
type Options struct {
	Version string
	Engine  string // "sqlite" or "postgres"
	DataDir string
	Token   string
	Checker *update.Checker
	// Docker: the image is updated, not the program file.
	Docker bool
	// Apply downloads, checks and installs a release; nil disables it.
	Apply func(ctx context.Context, r update.Release) (update.State, error)
	// Restart asks the server to shut down and start the installed program.
	Restart func()
	Log     *slog.Logger
	// CacheFor is how long a release check is reused; zero means 10 minutes.
	CacheFor time.Duration
}

type console struct {
	o  Options
	mu sync.Mutex
	// The last check, reused for a while so reloading the page stays cheap.
	latest    *update.Release
	checkErr  string
	checkedAt time.Time
	applying  bool
}

const cookieName = "geocrew_admin"

// Handler serves the console.
func Handler(o Options) http.Handler {
	if o.CacheFor == 0 {
		o.CacheFor = 10 * time.Minute
	}
	if o.Log == nil {
		o.Log = slog.Default()
	}
	c := &console{o: o}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", c.authed(c.index))
	mux.HandleFunc("POST /login", c.login)
	mux.HandleFunc("POST /update", c.authed(c.sameOrigin(c.apply)))
	mux.HandleFunc("GET /api/update", c.authed(c.status))
	return secure(mux)
}

func secure(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Cache-Control", "no-store")
		h.ServeHTTP(w, r)
	})
}

func (c *console) ok(r *http.Request) bool {
	got := ""
	if ck, err := r.Cookie(cookieName); err == nil {
		got = ck.Value
	} else if h := r.Header.Get("Authorization"); len(h) > 7 && h[:7] == "Bearer " {
		got = h[7:]
	}
	return c.o.Token != "" && subtle.ConstantTimeCompare([]byte(got), []byte(c.o.Token)) == 1
}

func (c *console) authed(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !c.ok(r) {
			if r.URL.Path == "/" {
				c.render(w, http.StatusOK, view{Login: true})
				return
			}
			http.Error(w, "需要管理令牌", http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}

// sameOrigin refuses form posts from other sites (the cookie is also
// SameSite=Strict).
func (c *console) sameOrigin(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s := r.Header.Get("Sec-Fetch-Site"); s != "" && s != "same-origin" && s != "none" {
			http.Error(w, "只能从管理页面本身提交", http.StatusForbidden)
			return
		}
		if o := r.Header.Get("Origin"); o != "" {
			if u, err := url.Parse(o); err != nil || u.Host != r.Host {
				http.Error(w, "只能从管理页面本身提交", http.StatusForbidden)
				return
			}
		}
		next(w, r)
	}
}

func (c *console) login(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	tok := r.PostFormValue("token")
	if c.o.Token == "" || subtle.ConstantTimeCompare([]byte(tok), []byte(c.o.Token)) != 1 {
		time.Sleep(time.Second) // slow down guessing
		c.render(w, http.StatusUnauthorized, view{Login: true, Error: "令牌不对"})
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name: cookieName, Value: tok, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode,
		Secure: r.TLS != nil, MaxAge: 12 * 3600,
	})
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

type view struct {
	Login    bool
	Error    string
	Notice   string
	Version  string
	Engine   string
	Platform string
	Docker   bool
	CanApply bool
	Applying bool

	Latest    *update.Release
	Newer     bool
	CheckErr  string
	CheckedAt time.Time

	Last    *update.State
	Refresh bool
}

func (c *console) render(w http.ResponseWriter, code int, v view) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(code)
	if err := page.Execute(w, v); err != nil {
		c.o.Log.Error("console page", "err", err)
	}
}

// check returns the newest release, from cache unless force.
func (c *console) check(ctx context.Context, force bool) (*update.Release, string, time.Time) {
	c.mu.Lock()
	if !force && !c.checkedAt.IsZero() && time.Since(c.checkedAt) < c.o.CacheFor {
		defer c.mu.Unlock()
		return c.latest, c.checkErr, c.checkedAt
	}
	c.mu.Unlock()
	r, err := c.o.Checker.Latest(ctx)
	c.mu.Lock()
	defer c.mu.Unlock()
	c.checkedAt = time.Now()
	c.latest, c.checkErr = nil, ""
	switch {
	case errors.Is(err, update.ErrNoRelease):
		c.checkErr = "还没有发布过正式版本"
	case err != nil:
		c.checkErr = "检查失败：" + err.Error()
	default:
		c.latest = &r
	}
	return c.latest, c.checkErr, c.checkedAt
}

func (c *console) base(ctx context.Context, force bool) view {
	v := view{
		Version: c.o.Version, Engine: c.o.Engine, Platform: runtime.GOOS + "/" + runtime.GOARCH,
		Docker: c.o.Docker, CanApply: c.o.Apply != nil && !c.o.Docker,
	}
	v.Latest, v.CheckErr, v.CheckedAt = c.check(ctx, force)
	if v.Latest != nil {
		v.Newer = update.Newer(v.Latest.Version, c.o.Version)
	}
	if st, ok, err := update.Load(c.o.DataDir); err == nil && ok {
		v.Last = &st
	}
	c.mu.Lock()
	v.Applying = c.applying
	c.mu.Unlock()
	return v
}

func (c *console) index(w http.ResponseWriter, r *http.Request) {
	c.render(w, http.StatusOK, c.base(r.Context(), r.URL.Query().Has("check")))
}

func (c *console) apply(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	want := r.PostFormValue("version")
	v := c.base(r.Context(), true)
	switch {
	case !v.CanApply:
		v.Error = "这种部署方式不能在这里更新"
	case v.Latest == nil:
		v.Error = "没有找到可安装的版本"
	case v.Latest.Version != want:
		v.Error = "最新版本已变为 " + v.Latest.Version + "，请看过更新说明后再点更新"
	case !v.Newer:
		v.Error = "已经是最新版本"
	}
	if v.Error != "" {
		c.render(w, http.StatusConflict, v)
		return
	}
	c.mu.Lock()
	if c.applying {
		c.mu.Unlock()
		v.Error = "正在更新"
		c.render(w, http.StatusConflict, v)
		return
	}
	c.applying = true
	c.mu.Unlock()
	defer func() { c.mu.Lock(); c.applying = false; c.mu.Unlock() }()

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
	defer cancel()
	st, err := c.o.Apply(ctx, *v.Latest)
	if err != nil {
		c.o.Log.Error("update failed", "to", want, "err", err)
		v.Error = "没有更新：" + err.Error()
		c.render(w, http.StatusInternalServerError, v)
		return
	}
	c.o.Log.Info("update installed, restarting", "from", st.From, "to", st.To)
	v.Last = &st
	v.Refresh = update.CanRestart && c.o.Restart != nil
	if v.Refresh {
		v.Notice = "已安装 " + st.To + "，正在重启。新版本启动后通过健康检查才算更新成功，否则自动退回 " + st.From + "。"
	} else {
		v.Notice = "已安装 " + st.To + "，请重启服务（如 systemctl restart geocrew-server）后生效。"
	}
	c.render(w, http.StatusOK, v)
	if v.Refresh {
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		go func() { time.Sleep(500 * time.Millisecond); c.o.Restart() }()
	}
}

type statusResp struct {
	Version     string        `json:"version"`
	Latest      string        `json:"latest,omitempty"`
	Newer       bool          `json:"newer"`
	PublishedAt *time.Time    `json:"published_at,omitempty"`
	Notes       string        `json:"notes,omitempty"`
	URL         string        `json:"url,omitempty"`
	Docker      bool          `json:"docker"`
	CheckError  string        `json:"check_error,omitempty"`
	Last        *update.State `json:"last_update,omitempty"`
}

func (c *console) status(w http.ResponseWriter, r *http.Request) {
	v := c.base(r.Context(), r.URL.Query().Has("check"))
	s := statusResp{Version: v.Version, Newer: v.Newer, Docker: v.Docker, CheckError: v.CheckErr, Last: v.Last}
	if v.Latest != nil {
		s.Latest, s.Notes, s.URL = v.Latest.Version, v.Latest.Notes, v.Latest.URL
		s.PublishedAt = &v.Latest.PublishedAt
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(s)
}

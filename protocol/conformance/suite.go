package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"time"
)

type result struct {
	section, name string
	err           error
	skip          string
}

type check struct {
	section, name string
	// capability the server must declare, "" for core protocol.
	needs string
	// workspace: needs the workspace and both members from the setup.
	workspace bool
	// v1: needs protocol v1 (v1.go), which servers add while v0 stays.
	v1  bool
	run func() error
}

// suite holds what earlier checks learned for later ones.
type suite struct {
	c    *client
	caps map[string]bool
	info map[string]any

	ws, invite           string
	leader, member       string // tokens
	leaderID, memberID   string
	leaderNick, memberNk string
	setupFailed          bool

	v1 *v1State // nil: the server does not serve protocol v1
}

func (s *suite) run() []result {
	var out []result
	for _, ch := range s.checks() {
		r := result{section: ch.section, name: ch.name}
		switch {
		case ch.needs != "" && s.caps != nil && !s.caps[ch.needs]:
			r.skip = "服务端未声明能力 " + ch.needs
		case ch.workspace && (s.ws == "" || s.member == ""):
			r.skip = "没有建好测试工作组"
		case ch.v1 && s.v1 == nil:
			r.skip = "服务端还没有协议 v1"
		default:
			r.err = ch.run()
			var sk skipped
			if errors.As(r.err, &sk) {
				r.err, r.skip = nil, string(sk)
			}
		}
		out = append(out, r)
	}
	return out
}

// skipped is what a check returns when it finds it cannot run here.
type skipped string

func (s skipped) Error() string { return string(s) }

func randomID() string {
	b := make([]byte, 16)
	rand.Read(b)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	h := hex.EncodeToString(b)
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

// expect checks a status code and, for errors, the section 1.1 machine code.
func expect(r response, status int, code string) error {
	if r.status != status {
		return fmt.Errorf("HTTP %d，应为 %d：%s", r.status, status, clip(r.body))
	}
	if code != "" && r.errorCode() != code {
		return fmt.Errorf("错误码 %q，应为 %q（第 1.1 节格式 {error, message}）：%s", r.errorCode(), code, clip(r.body))
	}
	return nil
}

func wsPath(ws, rest string) string { return "/api/v0/workspaces/" + ws + rest }

func feature(id string, version int, at time.Time, props map[string]any) map[string]any {
	return map[string]any{
		"type":       "Feature",
		"id":         id,
		"geometry":   map[string]any{"type": "Point", "coordinates": []float64{116.39, 39.91, 52.3}},
		"properties": props,
		"sync": map[string]any{
			"version":    version,
			"updated_at": at.UTC().Format(time.RFC3339Nano),
			"deleted":    false,
		},
	}
}

type pushResult struct {
	Results []struct {
		ID      string         `json:"id"`
		Status  string         `json:"status"`
		Seq     int64          `json:"seq"`
		Current map[string]any `json:"current"`
	} `json:"results"`
	Seq int64 `json:"seq"`
}

func (s *suite) push(token string, fs ...map[string]any) (pushResult, response, error) {
	var out pushResult
	r, err := s.c.do(http.MethodPost, wsPath(s.ws, "/features"), token, map[string]any{"features": fs}, nil)
	if err != nil {
		return out, r, err
	}
	if r.status != http.StatusOK {
		return out, r, fmt.Errorf("提交返回 HTTP %d：%s", r.status, clip(r.body))
	}
	if err := r.json(&out); err != nil {
		return out, r, err
	}
	if len(out.Results) != len(fs) {
		return out, r, fmt.Errorf("提交 %d 条，results 有 %d 条，应一一对应", len(fs), len(out.Results))
	}
	return out, r, nil
}

// pushOne pushes a single feature and wants the given status.
func (s *suite) pushOne(token string, f map[string]any, status string) (int64, map[string]any, error) {
	out, _, err := s.push(token, f)
	if err != nil {
		return 0, nil, err
	}
	got := out.Results[0]
	if got.ID != f["id"] {
		return 0, nil, fmt.Errorf("结果 id %q，应为 %q", got.ID, f["id"])
	}
	if got.Status != status {
		return 0, nil, fmt.Errorf("status %q，应为 %q", got.Status, status)
	}
	if got.Seq <= 0 {
		return 0, nil, fmt.Errorf("seq 为 %d，应为正数", got.Seq)
	}
	if out.Seq < got.Seq {
		return 0, nil, fmt.Errorf("响应的 seq %d 小于结果里的 seq %d", out.Seq, got.Seq)
	}
	return got.Seq, got.Current, nil
}

type pullResult struct {
	Features []map[string]any `json:"features"`
	Seq      int64            `json:"seq"`
	More     bool             `json:"more"`
}

func (s *suite) pull(token string, since int64, limit int) (pullResult, error) {
	var out pullResult
	path := fmt.Sprintf("/features?since=%d", since)
	if limit > 0 {
		path += fmt.Sprintf("&limit=%d", limit)
	}
	r, err := s.c.do(http.MethodGet, wsPath(s.ws, path), token, nil, nil)
	if err != nil {
		return out, err
	}
	if r.status != http.StatusOK {
		return out, fmt.Errorf("拉取返回 HTTP %d：%s", r.status, clip(r.body))
	}
	return out, r.json(&out)
}

// pullAll follows `more` from since and returns everything.
func (s *suite) pullAll(token string, since int64, limit int) ([]map[string]any, int64, error) {
	var all []map[string]any
	for i := 0; i < 1000; i++ {
		p, err := s.pull(token, since, limit)
		if err != nil {
			return nil, 0, err
		}
		if p.Seq < since {
			return nil, 0, fmt.Errorf("返回的 seq %d 小于请求的 since %d", p.Seq, since)
		}
		all = append(all, p.Features...)
		since = p.Seq
		if !p.More {
			return all, since, nil
		}
	}
	return nil, 0, errors.New("more 一直为 true")
}

func syncOf(f map[string]any) map[string]any {
	m, _ := f["sync"].(map[string]any)
	return m
}

func num(v any) float64 {
	f, _ := v.(float64)
	return f
}

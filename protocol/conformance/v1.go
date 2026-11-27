package main

import (
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// v1State is what the protocol v1 checks learn: a workspace of their own,
// made through v1, with a leader and a member.
type v1State struct {
	caps               map[string]bool
	ws, invite         string
	leader, member     string // tokens
	leaderID, memberID string
}

type v1Joined struct {
	Workspace struct {
		ID         string `json:"id"`
		Leader     string `json:"leader"`
		InviteCode string `json:"invite_code"`
	} `json:"workspace"`
	Member struct {
		ID    string   `json:"id"`
		Roles []string `json:"roles"`
	} `json:"member"`
	Token string `json:"token"`
}

func v1Path(ws, rest string) string { return "/api/v1/workspaces/" + ws + rest }

// v1Checks cover protocol v1 as far as servers serve it (protocol-v1.md).
// A server without /api/v1/info skips them.
func (s *suite) v1Checks() []check {
	ready := func() error {
		if s.v1.member == "" {
			return skipped("没有建好 v1 的测试工作区")
		}
		return nil
	}
	return []check{
		{section: "v1 §3.3", name: "/api/v1/info 声明协议 1、能力和上限", run: func() error {
			r, err := s.c.do(http.MethodGet, "/api/v1/info", "", nil, nil)
			if err != nil {
				return err
			}
			if r.status == http.StatusNotFound {
				return skipped("服务端还没有协议 v1")
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			var info struct {
				Protocol     int      `json:"protocol"`
				Capabilities []string `json:"capabilities"`
				Limits       *struct {
					MaxPushBatch int   `json:"max_push_batch"`
					MaxPullBatch int   `json:"max_pull_batch"`
					MaxFileSize  int64 `json:"max_file_size"`
				} `json:"limits"`
			}
			if err := r.json(&info); err != nil {
				return err
			}
			if info.Protocol != 1 {
				return fmt.Errorf("protocol 为 %d，应为 1", info.Protocol)
			}
			if info.Capabilities == nil || info.Limits == nil || info.Limits.MaxPushBatch < 1 || info.Limits.MaxPullBatch < 1 || info.Limits.MaxFileSize < 1 {
				return fmt.Errorf("缺 capabilities 或 limits：%s", clip(r.body))
			}
			s.v1 = &v1State{caps: map[string]bool{}}
			for _, c := range info.Capabilities {
				s.v1.caps[c] = true
			}
			return nil
		}},
		{section: "v1 §3.4", name: "建工作区：创建人是组长，看得到邀请码", v1: true, run: func() error {
			if !s.v1.caps["create"] {
				return skipped("服务端未声明能力 create")
			}
			r, err := s.c.do(http.MethodPost, "/api/v1/workspaces", "", map[string]any{"name": "一致性检查 v1", "nickname": "组长", "device": "conformance"}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusCreated, ""); err != nil {
				return err
			}
			var j v1Joined
			if err := r.json(&j); err != nil {
				return err
			}
			if j.Token == "" || j.Workspace.ID == "" || j.Workspace.Leader != j.Member.ID || j.Member.Roles == nil {
				return fmt.Errorf("返回的 {workspace, member, token} 不全，或组长不是创建人：%s", clip(r.body))
			}
			code := j.Workspace.InviteCode
			if len(code) != 8 || strings.ContainsAny(strings.ToUpper(code), lookAlikes) {
				return fmt.Errorf("邀请码 %q 应为 8 位，不含 0、O、1、I", code)
			}
			s.v1.ws, s.v1.invite, s.v1.leader, s.v1.leaderID = j.Workspace.ID, code, j.Token, j.Member.ID
			return nil
		}},
		{section: "v1 §3.4", name: "加入：邀请码不分大小写，新成员只有组员身份、看不到邀请码", v1: true, run: func() error {
			if s.v1.ws == "" {
				return skipped("没有建好 v1 的测试工作区")
			}
			r, err := s.c.do(http.MethodPost, "/api/v1/join", "", map[string]any{"invite_code": "WRQNG234", "nickname": "x", "device": "conformance"}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusNotFound, "bad_invite"); err != nil {
				return fmt.Errorf("错误的邀请码：%w", err)
			}
			r, err = s.c.do(http.MethodPost, "/api/v1/join", "", map[string]any{"invite_code": strings.ToLower(s.v1.invite), "nickname": "组员", "device": "conformance"}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			var j v1Joined
			if err := r.json(&j); err != nil {
				return err
			}
			if j.Token == "" || j.Member.ID == s.v1.leaderID || len(j.Member.Roles) != 0 || j.Workspace.InviteCode != "" {
				return fmt.Errorf("新成员应有自己的令牌、只有组员身份、看不到邀请码：%s", clip(r.body))
			}
			s.v1.member, s.v1.memberID = j.Token, j.Member.ID
			return nil
		}},
		{section: "v1 §3.4", name: "令牌：没有、错误或 v0 的令牌都是 401 unauthorized", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			tokens := []struct{ what, token string }{{"没有令牌", ""}, {"错误的令牌", "not-a-token"}}
			if s.leader != "" {
				tokens = append(tokens, struct{ what, token string }{"v0 的令牌", s.leader})
			}
			for _, t := range tokens {
				r, err := s.c.do(http.MethodGet, v1Path(s.v1.ws, ""), t.token, nil, nil)
				if err != nil {
					return err
				}
				if err := expect(r, http.StatusUnauthorized, "unauthorized"); err != nil {
					return fmt.Errorf("%s：%w", t.what, err)
				}
			}
			r, err := s.c.do(http.MethodGet, v1Path(s.v1.ws, ""), s.v1.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("组员读工作区：%w", err)
			}
			var ws struct {
				ID            string `json:"id"`
				AccessVersion *int64 `json:"access_version"`
			}
			if err := r.json(&ws); err != nil {
				return err
			}
			if ws.ID != s.v1.ws || ws.AccessVersion == nil {
				return fmt.Errorf("工作区信息应带 id 和 access_version：%s", clip(r.body))
			}
			return nil
		}},
		{section: "v1 §5.1", name: "改名和归档要 manage_workspace，重置邀请码要 manage_members，旧码作废", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			if r, err := s.c.do(http.MethodPatch, v1Path(s.v1.ws, ""), s.v1.member, map[string]any{"name": "组员改名"}, nil); err != nil {
				return err
			} else if err := expect(r, http.StatusForbidden, "forbidden"); err != nil {
				return fmt.Errorf("组员改名：%w", err)
			}
			if r, err := s.c.do(http.MethodPost, v1Path(s.v1.ws, "/invite/reset"), s.v1.member, nil, nil); err != nil {
				return err
			} else if err := expect(r, http.StatusForbidden, "forbidden"); err != nil {
				return fmt.Errorf("组员重置邀请码：%w", err)
			}
			r, err := s.c.do(http.MethodPatch, v1Path(s.v1.ws, ""), s.v1.leader, map[string]any{"name": "一致性检查 v1 改名", "archived": true}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("组长改名并归档：%w", err)
			}
			// Reopen whatever happens below, so a failure leaves the workspace usable.
			defer s.c.do(http.MethodPatch, v1Path(s.v1.ws, ""), s.v1.leader, map[string]any{"archived": false}, nil)
			var ws struct {
				Name       string `json:"name"`
				ArchivedAt string `json:"archived_at"`
			}
			if err := r.json(&ws); err != nil {
				return err
			}
			if _, err := time.Parse(time.RFC3339, ws.ArchivedAt); err != nil || ws.Name != "一致性检查 v1 改名" {
				return fmt.Errorf("改名并归档后应返回新名称和 archived_at：%s", clip(r.body))
			}
			if r, err := s.c.do(http.MethodPost, "/api/v1/join", "", map[string]any{"invite_code": s.v1.invite, "nickname": "归档后", "device": "conformance"}, nil); err != nil {
				return err
			} else if err := expect(r, http.StatusConflict, "archived"); err != nil {
				return fmt.Errorf("归档后加入：%w", err)
			}
			if r, err := s.c.do(http.MethodPatch, v1Path(s.v1.ws, ""), s.v1.leader, map[string]any{"archived": false}, nil); err != nil {
				return err
			} else if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("重新打开：%w", err)
			}
			r, err = s.c.do(http.MethodPost, v1Path(s.v1.ws, "/invite/reset"), s.v1.leader, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("组长重置邀请码：%w", err)
			}
			var reset struct {
				InviteCode string `json:"invite_code"`
			}
			if err := r.json(&reset); err != nil {
				return err
			}
			if reset.InviteCode == "" || reset.InviteCode == s.v1.invite {
				return errors.New("重置后应返回新的邀请码")
			}
			r, err = s.c.do(http.MethodPost, "/api/v1/join", "", map[string]any{"invite_code": s.v1.invite, "nickname": "旧码", "device": "conformance"}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusNotFound, "bad_invite"); err != nil {
				return fmt.Errorf("用旧邀请码加入：%w", err)
			}
			s.v1.invite = reset.InviteCode
			return nil
		}},
	}
}

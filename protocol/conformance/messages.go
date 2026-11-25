package main

import (
	"errors"
	"fmt"
	"net/http"
	"strings"
)

type comment struct {
	ID       string `json:"id"`
	Body     string `json:"body"`
	AuthorID string `json:"author_id"`
	Nickname string `json:"nickname"`
	Seq      int64  `json:"seq"`
	Deleted  bool   `json:"deleted"`
	Urgent   bool   `json:"urgent"`
	RollCall bool   `json:"roll_call"`
	AnswerTo string `json:"answer_to"`
}

func (s *suite) say(token string, body map[string]any) (response, error) {
	return s.c.do(http.MethodPost, wsPath(s.ws, "/comments"), token, body, nil)
}

func (s *suite) said(token string, body map[string]any) (comment, error) {
	var c comment
	r, err := s.say(token, body)
	if err != nil {
		return c, err
	}
	if err := expect(r, http.StatusOK, ""); err != nil {
		return c, err
	}
	return c, r.json(&c)
}

func (s *suite) comments(token string, since int64) ([]comment, error) {
	var out struct {
		Comments []comment `json:"comments"`
		Seq      int64     `json:"seq"`
		More     bool      `json:"more"`
	}
	r, err := s.c.do(http.MethodGet, wsPath(s.ws, fmt.Sprintf("/comments?since=%d", since)), token, nil, nil)
	if err != nil {
		return nil, err
	}
	if err := expect(r, http.StatusOK, ""); err != nil {
		return nil, err
	}
	return out.Comments, r.json(&out)
}

func (s *suite) messageChecks() []check {
	var first comment
	var viewToken string
	return []check{
		{section: "§8", name: "发言：服务端填作者和序号，同 id 重试返回原发言，不同内容 409 id_conflict", needs: "comments", workspace: true, run: func() error {
			id := randomID()
			c, err := s.said(s.member, map[string]any{"id": id, "body": "  明天看接触关系  "})
			if err != nil {
				return err
			}
			if c.ID != id || c.Body != "明天看接触关系" || c.AuthorID != s.memberID || c.Nickname != s.memberNk || c.Seq <= 0 {
				return fmt.Errorf("保存的发言不对（正文应去掉首尾空白，作者为提交者）：%+v", c)
			}
			again, err := s.said(s.member, map[string]any{"id": id, "body": "明天看接触关系"})
			if err != nil {
				return fmt.Errorf("重试：%w", err)
			}
			if again.Seq != c.Seq {
				return fmt.Errorf("重试得到 seq %d，应为原来的 %d", again.Seq, c.Seq)
			}
			r, err := s.say(s.member, map[string]any{"id": id, "body": "别的内容"})
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusConflict, "id_conflict"); err != nil {
				return err
			}
			r, err = s.say(s.member, map[string]any{"id": randomID(), "body": "   "})
			if err != nil {
				return err
			}
			if r.status != http.StatusBadRequest {
				return fmt.Errorf("空正文：HTTP %d，应为 400", r.status)
			}
			first = c
			return nil
		}},
		{section: "§8", name: "拉取发言；删除后留下 deleted: true、正文为空的记录", needs: "comments", workspace: true, run: func() error {
			if first.ID == "" {
				return errors.New("前一项没有发出发言")
			}
			r, err := s.c.do(http.MethodDelete, wsPath(s.ws, "/comments/"+first.ID), s.member, nil, nil)
			if err != nil {
				return err
			}
			if r.status != http.StatusNoContent && r.status != http.StatusOK {
				return fmt.Errorf("作者删除：HTTP %d：%s", r.status, clip(r.body))
			}
			cs, err := s.comments(s.leader, first.Seq-1)
			if err != nil {
				return err
			}
			for _, c := range cs {
				if c.ID == first.ID {
					if !c.Deleted || c.Body != "" || c.Seq <= first.Seq {
						return fmt.Errorf("删除后的记录应为 deleted、正文为空、seq 变大：%+v", c)
					}
					return nil
				}
			}
			return errors.New("拉取结果里没有这条发言")
		}},
		{section: "§8.1", name: "紧急消息只能发给全组，只读成员也能发", needs: "alerts", workspace: true, run: func() error {
			r, err := s.say(s.member, map[string]any{"id": randomID(), "body": "求助", "urgent": true, "feature_id": randomID()})
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusBadRequest, "invalid_comment"); err != nil {
				return fmt.Errorf("挂在记录上的紧急消息：%w", err)
			}
			if !s.caps["roles"] {
				return nil
			}
			if err := s.setRole(s.memberID, "readonly"); err != nil {
				return err
			}
			defer s.setRole(s.memberID, "member")
			r, err = s.say(s.member, map[string]any{"id": randomID(), "body": "普通发言"})
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusForbidden, ""); err != nil {
				return fmt.Errorf("只读成员普通发言：%w", err)
			}
			c, err := s.said(s.member, map[string]any{"id": randomID(), "body": "崴脚了", "urgent": true, "at": []float64{116.4, 39.9}})
			if err != nil {
				return fmt.Errorf("只读成员紧急消息：%w", err)
			}
			if !c.Urgent {
				return errors.New("返回的发言没有 urgent: true")
			}
			return nil
		}},
		{section: "§8.2", name: "点名只有组长能发起，回应要指向一条点名", needs: "rollcall", workspace: true, run: func() error {
			r, err := s.say(s.member, map[string]any{"id": randomID(), "body": "报平安", "roll_call": true})
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusForbidden, "forbidden"); err != nil {
				return fmt.Errorf("组员发起点名：%w", err)
			}
			call, err := s.said(s.leader, map[string]any{"id": randomID(), "body": "18 点前报平安", "roll_call": true})
			if err != nil {
				return fmt.Errorf("组长发起点名：%w", err)
			}
			if !call.RollCall {
				return errors.New("返回的点名没有 roll_call: true")
			}
			ans, err := s.said(s.member, map[string]any{"id": randomID(), "body": "平安", "answer_to": call.ID, "at": []float64{116.4, 39.9}})
			if err != nil {
				return fmt.Errorf("回应：%w", err)
			}
			if ans.AnswerTo != call.ID {
				return fmt.Errorf("answer_to 为 %q，应为 %q", ans.AnswerTo, call.ID)
			}
			r, err = s.say(s.member, map[string]any{"id": randomID(), "body": "平安", "answer_to": randomID()})
			if err != nil {
				return err
			}
			return expect(r, http.StatusBadRequest, "invalid_comment")
		}},
		{section: "§10", name: "查看链接：v_ 令牌只能读，越界时长 400 bad_hours", needs: "view", workspace: true, run: func() error {
			r, err := s.c.do(http.MethodPost, wsPath(s.ws, "/view-links"), s.member, map[string]any{"hours": 0}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusBadRequest, "bad_hours"); err != nil {
				return err
			}
			r, err = s.c.do(http.MethodPost, wsPath(s.ws, "/view-links"), s.member, map[string]any{"hours": 2}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusCreated, ""); err != nil {
				return err
			}
			var v struct {
				Token, Path string
				ExpiresAt   string `json:"expires_at"`
			}
			if err := r.json(&v); err != nil {
				return err
			}
			if !strings.HasPrefix(v.Token, "v_") || v.ExpiresAt == "" || !strings.Contains(v.Path, "#") || strings.Contains(strings.SplitN(v.Path, "#", 2)[0], v.Token) {
				return fmt.Errorf("令牌应以 v_ 开头、带 expires_at，path 里令牌放在 # 后面：%+v", v)
			}
			viewToken = v.Token
			if r, err = s.c.do(http.MethodGet, wsPath(s.ws, "/features?since=0"), viewToken, nil, nil); err != nil {
				return err
			} else if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("用查看令牌拉取：%w", err)
			}
			writes := []struct {
				method, path string
				body         any
			}{
				{http.MethodPost, "/features", map[string]any{"features": []any{feature(randomID(), 1, nowish(), map[string]any{})}}},
				{http.MethodPost, "/view-links", map[string]any{}},
			}
			if s.caps["comments"] {
				writes = append(writes, struct {
					method, path string
					body         any
				}{http.MethodPost, "/comments", map[string]any{"id": randomID(), "body": "x"}})
			}
			for _, w := range writes {
				r, err := s.c.do(w.method, wsPath(s.ws, w.path), viewToken, w.body, nil)
				if err != nil {
					return err
				}
				if r.status != http.StatusForbidden {
					return fmt.Errorf("用查看令牌 %s %s：HTTP %d，应为 403", w.method, w.path, r.status)
				}
			}
			return nil
		}},
		{section: "§10", name: "撤销查看链接后令牌失效", needs: "view", workspace: true, run: func() error {
			if viewToken == "" {
				return errors.New("前一项没有生成查看链接")
			}
			r, err := s.c.do(http.MethodDelete, wsPath(s.ws, "/view-links"), s.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			var v struct {
				Revoked int `json:"revoked"`
			}
			if err := r.json(&v); err != nil {
				return err
			}
			if v.Revoked != 1 {
				return fmt.Errorf("revoked 为 %d，应为 1", v.Revoked)
			}
			r, err = s.c.do(http.MethodGet, wsPath(s.ws, ""), viewToken, nil, nil)
			if err != nil {
				return err
			}
			return expect(r, http.StatusUnauthorized, "")
		}},
	}
}

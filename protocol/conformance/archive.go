package main

import (
	"errors"
	"fmt"
	"net/http"
	"time"
)

// archiveChecks run last: they archive the test workspace and reopen it.
func (s *suite) archiveChecks() []check {
	return []check{
		{section: "§2.3", name: "归档后拒绝提交、设置、发言和加入，读取照常；重新打开后可以提交", needs: "archive", workspace: true, run: func() error {
			if r, err := s.c.do(http.MethodPatch, wsPath(s.ws, ""), s.member, map[string]any{"archived": true}, nil); err != nil {
				return err
			} else if err := expect(r, http.StatusForbidden, ""); err != nil {
				return fmt.Errorf("组员归档：%w", err)
			}
			var ws struct {
				Name       string `json:"name"`
				ArchivedAt string `json:"archived_at"`
			}
			r, err := s.c.do(http.MethodPatch, wsPath(s.ws, ""), s.leader, map[string]any{"archived": true}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("组长归档：%w", err)
			}
			if err := r.json(&ws); err != nil {
				return err
			}
			if _, err := time.Parse(time.RFC3339, ws.ArchivedAt); err != nil {
				return fmt.Errorf("归档后 archived_at 为 %q，应为 RFC 3339 时间", ws.ArchivedAt)
			}
			// Reopen whatever happens below, so a failure leaves the workspace usable.
			defer s.c.do(http.MethodPatch, wsPath(s.ws, ""), s.leader, map[string]any{"archived": false}, nil)

			_, pr, err := s.push(s.member, feature(randomID(), 1, nowish(), nil))
			if err != nil && pr.status == 0 {
				return err
			}
			if err := expect(pr, http.StatusConflict, "archived"); err != nil {
				return fmt.Errorf("归档后提交要素：%w", err)
			}
			for _, w := range []struct {
				what, method, path string
				body               any
			}{
				{"修改设置", http.MethodPut, "/settings", map[string]any{"settings": map[string]any{}}},
				{"发言", http.MethodPost, "/comments", map[string]any{"id": randomID(), "body": "归档后发言"}},
			} {
				r, err := s.c.do(w.method, wsPath(s.ws, w.path), s.leader, w.body, nil)
				if err != nil {
					return err
				}
				if err := expect(r, http.StatusConflict, "archived"); err != nil {
					return fmt.Errorf("归档后%s：%w", w.what, err)
				}
			}
			if s.invite != "" {
				r, err := s.c.do(http.MethodPost, "/api/v0/workspaces/join", "", map[string]any{"invite_code": s.invite, "nickname": "归档后加入"}, nil)
				if err != nil {
					return err
				}
				if err := expect(r, http.StatusConflict, "archived"); err != nil {
					return fmt.Errorf("归档后加入：%w", err)
				}
			}
			if r, err := s.c.do(http.MethodGet, wsPath(s.ws, "/features"), s.member, nil, nil); err != nil {
				return err
			} else if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("归档后拉取：%w", err)
			}

			r, err = s.c.do(http.MethodPatch, wsPath(s.ws, ""), s.leader, map[string]any{"archived": false}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("重新打开：%w", err)
			}
			var open map[string]any
			if err := r.json(&open); err != nil {
				return err
			}
			if _, ok := open["archived_at"]; ok {
				return errors.New("重新打开后仍有 archived_at")
			}
			if _, pr, err := s.push(s.member, feature(randomID(), 1, nowish(), nil)); err != nil {
				return fmt.Errorf("重新打开后提交：%w（状态 %d）", err, pr.status)
			}
			return nil
		}},
	}
}

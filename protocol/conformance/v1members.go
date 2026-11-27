package main

import (
	"fmt"
	"net/http"
)

// v1MemberChecks cover members (v1 5.2) and devices (5.3) in the workspace
// v1Checks made. They run last: the member ends up on a new device.
func (s *suite) v1MemberChecks() []check {
	ready := func() error {
		if s.v1 == nil || s.v1.member == "" {
			return skipped("没有建好 v1 的测试工作区")
		}
		return nil
	}
	return []check{
		{section: "v1 §5.2", name: "成员列表人人能读；昵称只能改自己的", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			r, err := s.c.do(http.MethodGet, v1Path(s.v1.ws, "/members"), s.v1.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			var members []struct {
				ID    string   `json:"id"`
				Roles []string `json:"roles"`
			}
			if err := r.json(&members); err != nil {
				return err
			}
			if len(members) < 2 {
				return fmt.Errorf("成员列表应至少有组长和组员：%s", clip(r.body))
			}
			r, err = s.c.do(http.MethodPatch, v1Path(s.v1.ws, "/members/"+s.v1.leaderID), s.v1.member, map[string]any{"nickname": "冒名"}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusForbidden, "forbidden"); err != nil {
				return fmt.Errorf("改别人的昵称：%w", err)
			}
			r, err = s.c.do(http.MethodPatch, v1Path(s.v1.ws, "/members/"+s.v1.memberID), s.v1.member, map[string]any{"nickname": "组员甲"}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("改自己的昵称：%w", err)
			}
			return nil
		}},
		{section: "v1 §5.2", name: "组长不能直接退出，要先转让：409 leader_must_transfer", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			r, err := s.c.do(http.MethodDelete, v1Path(s.v1.ws, "/members/"+s.v1.leaderID), s.v1.leader, nil, nil)
			if err != nil {
				return err
			}
			return expect(r, http.StatusConflict, "leader_must_transfer")
		}},
		{section: "v1 §5.3", name: "转移码只有本人和组长能生成；新手机用它加入，旧设备吊销后令牌失效", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			r, err := s.c.do(http.MethodPost, v1Path(s.v1.ws, "/members/"+s.v1.leaderID+"/transfer-code"), s.v1.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusForbidden, "forbidden"); err != nil {
				return fmt.Errorf("给别人生成转移码：%w", err)
			}
			r, err = s.c.do(http.MethodPost, v1Path(s.v1.ws, "/members/"+s.v1.memberID+"/transfer-code"), s.v1.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusCreated, ""); err != nil {
				return fmt.Errorf("给自己生成转移码：%w", err)
			}
			var code struct {
				Code string `json:"code"`
			}
			if err := r.json(&code); err != nil {
				return err
			}
			if len(code.Code) != 12 {
				return fmt.Errorf("转移码 %q 应为 12 位", code.Code)
			}
			r, err = s.c.do(http.MethodPost, "/api/v1/join", "", map[string]any{"transfer_code": code.Code, "device": "conformance 新手机"}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("用转移码加入：%w", err)
			}
			var j v1Joined
			if err := r.json(&j); err != nil {
				return err
			}
			if j.Member.ID != s.v1.memberID || j.Token == "" {
				return fmt.Errorf("用转移码加入应是同一个成员的新令牌：%s", clip(r.body))
			}
			r, err = s.c.do(http.MethodGet, v1Path(s.v1.ws, "/members/"+s.v1.memberID+"/devices"), j.Token, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("读自己的设备：%w", err)
			}
			var devices []struct {
				ID     string `json:"id"`
				Device string `json:"device"`
			}
			if err := r.json(&devices); err != nil {
				return err
			}
			if len(devices) != 2 {
				return fmt.Errorf("应有两台设备：%s", clip(r.body))
			}
			old := devices[0].ID
			if devices[0].Device == "conformance 新手机" {
				old = devices[1].ID
			}
			r, err = s.c.do(http.MethodDelete, v1Path(s.v1.ws, "/devices/"+old), j.Token, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusNoContent, ""); err != nil {
				return fmt.Errorf("吊销旧设备：%w", err)
			}
			r, err = s.c.do(http.MethodGet, v1Path(s.v1.ws, ""), s.v1.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusUnauthorized, "unauthorized"); err != nil {
				return fmt.Errorf("吊销后旧令牌：%w", err)
			}
			s.v1.member = j.Token
			return nil
		}},
	}
}

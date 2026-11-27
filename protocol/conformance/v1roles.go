package main

import (
	"fmt"
	"net/http"
)

// v1RoleChecks cover roles (v1 5.4), layer overrides (5.5) and the audit log
// (5.7) in the workspace v1Checks made.
func (s *suite) v1RoleChecks() []check {
	ready := func() error {
		if s.v1 == nil || s.v1.member == "" {
			return skipped("没有建好 v1 的测试工作区")
		}
		return nil
	}
	var role struct {
		ID      string `json:"id"`
		Name    string `json:"name"`
		Version int64  `json:"version"`
	}
	return []check{
		{section: "v1 §5.4", name: "建身份要 manage_roles；改身份带 base_version，版本不对返回 409 stale", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			body := map[string]any{"name": "一致性采样员", "position": 5, "permissions": map[string]any{"edit_rows": "own", "export_data": true}}
			r, err := s.c.do(http.MethodPost, v1Path(s.v1.ws, "/roles"), s.v1.member, body, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusForbidden, "forbidden"); err != nil {
				return fmt.Errorf("组员建身份：%w", err)
			}
			r, err = s.c.do(http.MethodPost, v1Path(s.v1.ws, "/roles"), s.v1.leader, body, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusCreated, ""); err != nil {
				return fmt.Errorf("组长建身份：%w", err)
			}
			if err := r.json(&role); err != nil {
				return err
			}
			r, err = s.c.do(http.MethodPatch, v1Path(s.v1.ws, "/roles/"+role.ID), s.v1.leader, map[string]any{"name": "一致性采样组", "base_version": role.Version}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("改身份：%w", err)
			}
			r, err = s.c.do(http.MethodPatch, v1Path(s.v1.ws, "/roles/"+role.ID), s.v1.leader, map[string]any{"name": "旧版本", "base_version": role.Version}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusConflict, "stale"); err != nil {
				return fmt.Errorf("按旧版本改身份：%w", err)
			}
			r, err = s.c.do(http.MethodDelete, v1Path(s.v1.ws, "/roles/member"), s.v1.leader, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusBadRequest, "invalid"); err != nil {
				return fmt.Errorf("删基础身份：%w", err)
			}
			return nil
		}},
		{section: "v1 §5.5", name: "图层覆盖：能看到图层的人能读，组员不能设", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			if role.ID == "" {
				return skipped("上一项没能建身份")
			}
			layer := randomID()
			r, err := s.c.do(http.MethodPost, v1Path(s.v1.ws, "/layers"), s.v1.leader, map[string]any{"id": layer, "name": "覆盖", "geometry_type": "Point", "crs": "EPSG:4490", "fields": []any{}}, nil)
			if err != nil {
				return err
			}
			if r.status != http.StatusOK && r.status != http.StatusCreated {
				return fmt.Errorf("建图层：HTTP %d %s", r.status, clip(r.body))
			}
			path := v1Path(s.v1.ws, "/layers/"+layer+"/overrides")
			r, err = s.c.do(http.MethodPut, path+"/"+role.ID, s.v1.member, map[string]any{"edit_rows": "none"}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusForbidden, "forbidden"); err != nil {
				return fmt.Errorf("组员设覆盖：%w", err)
			}
			r, err = s.c.do(http.MethodPut, path+"/"+role.ID, s.v1.leader, map[string]any{"edit_rows": "none"}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusNoContent, ""); err != nil {
				return fmt.Errorf("组长设覆盖：%w", err)
			}
			r, err = s.c.do(http.MethodGet, path, s.v1.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("组员读覆盖：%w", err)
			}
			var overrides map[string]map[string]any
			if err := r.json(&overrides); err != nil {
				return err
			}
			if overrides[role.ID]["edit_rows"] != "none" {
				return fmt.Errorf("覆盖应为 edit_rows: none：%s", clip(r.body))
			}
			return nil
		}},
		{section: "v1 §5.7", name: "操作日志只有组长能读，按时间倒序", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			r, err := s.c.do(http.MethodGet, v1Path(s.v1.ws, "/audit"), s.v1.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusForbidden, "forbidden"); err != nil {
				return fmt.Errorf("组员读日志：%w", err)
			}
			r, err = s.c.do(http.MethodGet, v1Path(s.v1.ws, "/audit?limit=10"), s.v1.leader, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("组长读日志：%w", err)
			}
			var entries []struct {
				ID     int64  `json:"id"`
				Action string `json:"action"`
			}
			if err := r.json(&entries); err != nil {
				return err
			}
			if len(entries) == 0 {
				return fmt.Errorf("建、改身份和设覆盖之后日志不应为空")
			}
			for i := 1; i < len(entries); i++ {
				if entries[i].ID >= entries[i-1].ID {
					return fmt.Errorf("日志应按时间倒序：%s", clip(r.body))
				}
			}
			return nil
		}},
	}
}

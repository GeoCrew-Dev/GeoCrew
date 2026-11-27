package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

type v1PushResult struct {
	ID        string            `json:"id"`
	Status    string            `json:"status"`
	Seq       int64             `json:"seq"`
	Conflicts []json.RawMessage `json:"conflicts"`
	Error     string            `json:"error"`
}

type v1Pulled struct {
	Layers []map[string]any `json:"layers"`
	Rows   []struct {
		ID         string                     `json:"id"`
		Conflicted bool                       `json:"conflicted"`
		Fields     map[string]json.RawMessage `json:"fields"`
	} `json:"rows"`
	Conflicts []struct {
		ID   string `json:"id"`
		Row  string `json:"row"`
		Kind string `json:"kind"`
	} `json:"conflicts"`
	Seq  int64 `json:"seq"`
	More *bool `json:"more"`
}

// v1RowChecks cover layers (v1 5.6, 7.3), push (8.1), pull (8.2) and
// conflicts (8.3) in the workspace v1Checks made.
func (s *suite) v1RowChecks() []check {
	layer, row := randomID(), randomID()
	var seq int64 // the pull position after the first push
	push := func(token string, change map[string]any, now time.Time) (v1PushResult, error) {
		r, err := s.c.do(http.MethodPost, v1Path(s.v1.ws, "/rows"), token, map[string]any{"client_now": now.Format(time.RFC3339), "changes": []any{change}}, nil)
		if err != nil {
			return v1PushResult{}, err
		}
		if err := expect(r, http.StatusOK, ""); err != nil {
			return v1PushResult{}, err
		}
		var out struct {
			Results []v1PushResult `json:"results"`
		}
		if err := r.json(&out); err != nil {
			return v1PushResult{}, err
		}
		if len(out.Results) != 1 {
			return v1PushResult{}, fmt.Errorf("一条修改应有一条结果：%s", clip(r.body))
		}
		return out.Results[0], nil
	}
	change := func(id string, base int64, value string, at time.Time) map[string]any {
		return map[string]any{"id": id, "layer": layer, "row": row, "base_seq": base,
			"set": map[string]any{"f1": map[string]any{"value": value, "edited_at": at.Format(time.RFC3339)}}}
	}
	pull := func(token string, since int64) (v1Pulled, error) {
		r, err := s.c.do(http.MethodGet, v1Path(s.v1.ws, fmt.Sprintf("/changes?since=%d", since)), token, nil, nil)
		if err != nil {
			return v1Pulled{}, err
		}
		if err := expect(r, http.StatusOK, ""); err != nil {
			return v1Pulled{}, err
		}
		var p v1Pulled
		if err := r.json(&p); err != nil {
			return p, err
		}
		if p.More == nil {
			return p, fmt.Errorf("拉取结果缺 more：%s", clip(r.body))
		}
		return p, nil
	}
	ready := func() error {
		if s.v1 == nil || s.v1.member == "" {
			return skipped("没有建好 v1 的测试工作区")
		}
		return nil
	}
	return []check{
		{section: "v1 §5.6", name: "建共享图层要 manage_layers；同一 id 重试返回原图层，结构不同返回 409 stale", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			body := map[string]any{"id": layer, "name": "野外点", "geometry_type": "Point", "crs": "EPSG:4490",
				"fields": []any{map[string]any{"id": "f1", "name": "点名", "type": "string"}}}
			r, err := s.c.do(http.MethodPost, v1Path(s.v1.ws, "/layers"), s.v1.member, body, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusForbidden, "forbidden"); err != nil {
				return fmt.Errorf("组员建图层：%w", err)
			}
			for i := 0; i < 2; i++ {
				r, err := s.c.do(http.MethodPost, v1Path(s.v1.ws, "/layers"), s.v1.leader, body, nil)
				if err != nil {
					return err
				}
				if r.status != http.StatusOK && r.status != http.StatusCreated {
					return fmt.Errorf("组长建图层（第 %d 次）：HTTP %d %s", i+1, r.status, clip(r.body))
				}
				var l struct {
					SchemaVersion int64 `json:"schema_version"`
				}
				if err := r.json(&l); err != nil {
					return err
				}
				if l.SchemaVersion != 1 {
					return fmt.Errorf("新图层的 schema_version 为 %d，应为 1", l.SchemaVersion)
				}
			}
			body["geometry_type"] = "LineString"
			r, err = s.c.do(http.MethodPost, v1Path(s.v1.ws, "/layers"), s.v1.leader, body, nil)
			if err != nil {
				return err
			}
			return expect(r, http.StatusConflict, "stale")
		}},
		{section: "v1 §8.1", name: "提交新行得到 applied 和 seq，同一修改 ID 重试是 duplicate，组员拉取看得到", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			now := nowish()
			id := randomID()
			res, err := push(s.v1.leader, change(id, 0, "D001", now.Add(-time.Minute)), now)
			if err != nil {
				return err
			}
			if res.Status != "applied" || res.Seq < 1 || res.Conflicts == nil {
				return fmt.Errorf("结果应为 applied，带 seq 和 conflicts：%+v", res)
			}
			again, err := push(s.v1.leader, change(id, 0, "D001", now.Add(-time.Minute)), now)
			if err != nil {
				return err
			}
			if again.Status != "duplicate" || again.Seq != res.Seq {
				return fmt.Errorf("重试应为 duplicate，seq 为 %d：%+v", res.Seq, again)
			}
			p, err := pull(s.v1.member, 0)
			if err != nil {
				return err
			}
			for _, r := range p.Rows {
				if r.ID == row {
					seq = p.Seq
					return nil
				}
			}
			return fmt.Errorf("组员拉取不到刚提交的行 %s", row)
		}},
		{section: "v1 §6.4", name: "同一字段的并发修改：编辑时间晚的为准，落败的进冲突记录，选 lost 换回来", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			if seq == 0 {
				return skipped("上一项没能提交")
			}
			now := nowish()
			res, err := push(s.v1.member, change(randomID(), 0, "D009", now.Add(-time.Hour)), now)
			if err != nil {
				return err
			}
			if res.Status != "applied" || len(res.Conflicts) != 1 {
				return fmt.Errorf("更早的修改应 applied 并带一条冲突：%+v", res)
			}
			p, err := pull(s.v1.member, seq)
			if err != nil {
				return err
			}
			if len(p.Rows) != 1 || !p.Rows[0].Conflicted || len(p.Conflicts) != 1 || p.Conflicts[0].Kind != "value" {
				return fmt.Errorf("拉取应有一行标为有冲突，和一条 value 冲突：%+v", p)
			}
			var kept struct {
				Value string `json:"value"`
			}
			if err := json.Unmarshal(p.Rows[0].Fields["f1"], &kept); err != nil || kept.Value != "D001" {
				return fmt.Errorf("应保留编辑时间晚的 D001：%s", p.Rows[0].Fields["f1"])
			}
			r, err := s.c.do(http.MethodPost, v1Path(s.v1.ws, "/conflicts/"+p.Conflicts[0].ID+"/resolve"), s.v1.member, map[string]any{"choose": "lost"}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("当事人处理冲突：%w", err)
			}
			p, err = pull(s.v1.member, p.Seq)
			if err != nil {
				return err
			}
			if len(p.Rows) != 1 || p.Rows[0].Conflicted || json.Unmarshal(p.Rows[0].Fields["f1"], &kept) != nil || kept.Value != "D009" {
				return fmt.Errorf("选 lost 后应换成 D009、不再有冲突：%+v", p)
			}
			return nil
		}},
		{section: "v1 §7.3", name: "结构变更要 edit_schema，带 base_version；版本不对返回 409 stale", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			ops := map[string]any{"base_version": 1, "ops": []any{map[string]any{"op": "rename_field", "id": "f1", "name": "点号"}}}
			path := v1Path(s.v1.ws, "/layers/"+layer+"/schema")
			if r, err := s.c.do(http.MethodPost, path, s.v1.member, ops, nil); err != nil {
				return err
			} else if err := expect(r, http.StatusForbidden, "forbidden"); err != nil {
				return fmt.Errorf("组员改结构：%w", err)
			}
			r, err := s.c.do(http.MethodPost, path, s.v1.leader, ops, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("组长改结构：%w", err)
			}
			var l struct {
				SchemaVersion int64 `json:"schema_version"`
			}
			if err := r.json(&l); err != nil {
				return err
			}
			if l.SchemaVersion != 2 {
				return fmt.Errorf("改结构后 schema_version 为 %d，应为 2", l.SchemaVersion)
			}
			r, err = s.c.do(http.MethodPost, path, s.v1.leader, ops, nil)
			if err != nil {
				return err
			}
			return expect(r, http.StatusConflict, "stale")
		}},
	}
}

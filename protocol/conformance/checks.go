package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"reflect"
	"strings"
	"time"
)

// inviteAlphabet: 8 characters without the look-alikes 0/O and 1/I (section 2).
const lookAlikes = "0O1I"

func nowish() time.Time { return time.Now().UTC().Truncate(time.Second) }

func (s *suite) checks() []check {
	t0 := time.Now().UTC().Add(-time.Hour).Truncate(time.Second)
	var main string // a feature edited through the sync checks
	var mainSeq int64
	core := []check{
		{section: "§3", name: "健康检查 /healthz 返回 ok", run: func() error {
			r, err := s.c.do(http.MethodGet, "/healthz", "", nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			if strings.TrimSpace(string(r.body)) != "ok" {
				return fmt.Errorf("正文 %q，应为 ok", clip(r.body))
			}
			return nil
		}},
		{section: "§3", name: "/api/v0/info 声明协议版本和能力", run: func() error {
			r, err := s.c.do(http.MethodGet, "/api/v0/info", "", nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			var info struct {
				Name         string   `json:"name"`
				Protocol     string   `json:"protocol"`
				Capabilities []string `json:"capabilities"`
			}
			if err := r.json(&info); err != nil {
				return err
			}
			s.caps = map[string]bool{}
			for _, c := range info.Capabilities {
				s.caps[c] = true
			}
			switch {
			case info.Protocol != "0":
				return fmt.Errorf("protocol 为 %q，应为 \"0\"", info.Protocol)
			case info.Name == "":
				return errors.New("缺少 name")
			case !s.caps["sync"]:
				return errors.New("capabilities 里没有 sync")
			}
			return nil
		}},
		{section: "§2", name: "新建工作组返回组长令牌和 8 位邀请码", needs: "create", run: func() error {
			s.leaderNick = "检查组长"
			r, err := s.c.do(http.MethodPost, "/api/v0/workspaces", "", map[string]any{
				"name": "conformance " + time.Now().UTC().Format("2006-01-02 15:04"), "nickname": s.leaderNick,
			}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusCreated, ""); err != nil {
				return err
			}
			var j joinResult
			if err := r.json(&j); err != nil {
				return err
			}
			if err := j.valid("leader"); err != nil {
				return err
			}
			if len(j.InviteCode) != 8 || strings.ContainsAny(strings.ToUpper(j.InviteCode), lookAlikes) {
				return fmt.Errorf("邀请码 %q 应为 8 位且不含 0、O、1、I", j.InviteCode)
			}
			s.ws, s.invite, s.leader, s.leaderID = j.WorkspaceID, j.InviteCode, j.Token, j.MemberID
			return nil
		}},
		{section: "§2", name: "用小写邀请码加入工作组", needs: "create", run: func() error {
			if s.ws == "" {
				return errors.New("没有建好工作组")
			}
			s.memberNk = "检查组员"
			r, err := s.c.do(http.MethodPost, "/api/v0/workspaces/join", "", map[string]any{
				"invite_code": strings.ToLower(s.invite), "nickname": s.memberNk,
			}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			var j joinResult
			if err := r.json(&j); err != nil {
				return err
			}
			if err := j.valid("member"); err != nil {
				return err
			}
			if j.WorkspaceID != s.ws {
				return fmt.Errorf("workspace_id %q，应为 %q", j.WorkspaceID, s.ws)
			}
			if j.InviteCode != "" {
				return errors.New("加入时不应返回邀请码")
			}
			if j.MemberID == s.leaderID || j.Token == s.leader {
				return errors.New("组员和组长的 member_id 或令牌相同")
			}
			s.member, s.memberID = j.Token, j.MemberID
			return nil
		}},
		{section: "§1.1", name: "错误的邀请码返回 404 bad_invite", run: func() error {
			r, err := s.c.do(http.MethodPost, "/api/v0/workspaces/join", "", map[string]any{"invite_code": "ZZZZZZZZ", "nickname": "x"}, nil)
			if err != nil {
				return err
			}
			return expect(r, http.StatusNotFound, "bad_invite")
		}},
		{section: "§1.1", name: "没有或错误的令牌返回 401 unauthorized", workspace: true, run: func() error {
			for _, tok := range []string{"", "not-a-token"} {
				r, err := s.c.do(http.MethodGet, wsPath(s.ws, ""), tok, nil, nil)
				if err != nil {
					return err
				}
				if err := expect(r, http.StatusUnauthorized, "unauthorized"); err != nil {
					return err
				}
			}
			return nil
		}},
		{section: "§3", name: "工作组信息：只有组长看到邀请码", workspace: true, run: func() error {
			for _, who := range []struct {
				token, id, role string
				invite          bool
			}{{s.leader, s.leaderID, "leader", true}, {s.member, s.memberID, "member", false}} {
				r, err := s.c.do(http.MethodGet, wsPath(s.ws, ""), who.token, nil, nil)
				if err != nil {
					return err
				}
				if err := expect(r, http.StatusOK, ""); err != nil {
					return err
				}
				var v struct {
					ID         string `json:"id"`
					InviteCode string `json:"invite_code"`
					Me         struct {
						ID   string `json:"id"`
						Role string `json:"role"`
					} `json:"me"`
					Online []string `json:"online"`
				}
				if err := r.json(&v); err != nil {
					return err
				}
				if v.ID != s.ws || v.Me.ID != who.id || v.Me.Role != who.role {
					return fmt.Errorf("%s 看到 id=%q me=%+v", who.role, v.ID, v.Me)
				}
				if (v.InviteCode != "") != who.invite {
					return fmt.Errorf("%s 是否看到邀请码：%v，应为 %v", who.role, v.InviteCode != "", who.invite)
				}
				if v.Online == nil {
					return errors.New("缺少 online 数组")
				}
			}
			return nil
		}},
		{section: "§3", name: "成员列表列出两人的昵称和角色", workspace: true, run: func() error {
			r, err := s.c.do(http.MethodGet, wsPath(s.ws, "/members"), s.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			var ms []struct{ ID, Nickname, Role string }
			if err := r.json(&ms); err != nil {
				return err
			}
			want := map[string]string{s.leaderID: s.leaderNick + "/leader", s.memberID: s.memberNk + "/member"}
			for _, m := range ms {
				if w, ok := want[m.ID]; ok && w == m.Nickname+"/"+m.Role {
					delete(want, m.ID)
				}
			}
			if len(want) > 0 {
				return fmt.Errorf("没找到：%v", want)
			}
			return nil
		}},
		{section: "§4.2", name: "提交新要素返回 applied 和 seq", workspace: true, run: func() error {
			main = randomID()
			f := feature(main, 1, t0, map[string]any{"kind": "geo_point", "title": "D001", "note": "灰岩，层理清晰 🪨"})
			syncOf(f)["updated_by"] = "someone-else"
			seq, _, err := s.pushOne(s.member, f, "applied")
			mainSeq = seq
			return err
		}},
		{section: "§4.2", name: "重复提交同一版本返回 duplicate", workspace: true, run: func() error {
			f := feature(main, 1, t0, map[string]any{"kind": "geo_point", "title": "D001", "note": "灰岩，层理清晰 🪨"})
			seq, _, err := s.pushOne(s.member, f, "duplicate")
			if err == nil && seq != mainSeq {
				return fmt.Errorf("duplicate 的 seq %d，应为原来的 %d", seq, mainSeq)
			}
			return err
		}},
		{section: "§4.2", name: "同一请求里 id 重复时拒绝", workspace: true, run: func() error {
			id := randomID()
			r, err := s.c.do(http.MethodPost, wsPath(s.ws, "/features"), s.member, map[string]any{"features": []any{
				feature(id, 1, t0, map[string]any{}), feature(id, 2, t0, map[string]any{}),
			}}, nil)
			if err != nil {
				return err
			}
			if r.status != http.StatusBadRequest {
				return fmt.Errorf("HTTP %d，应为 400", r.status)
			}
			return nil
		}},
		{section: "§4.1", name: "拉取时 properties 原样返回，updated_by 是提交者", workspace: true, run: func() error {
			all, _, err := s.pullAll(s.leader, 0, 0)
			if err != nil {
				return err
			}
			f := find(all, main)
			if f == nil {
				return errors.New("拉取结果里没有刚提交的要素")
			}
			want := map[string]any{"kind": "geo_point", "title": "D001", "note": "灰岩，层理清晰 🪨"}
			if !reflect.DeepEqual(f["properties"], want) {
				return fmt.Errorf("properties 为 %v，应为 %v", f["properties"], want)
			}
			if by := syncOf(f)["updated_by"]; by != s.memberID {
				return fmt.Errorf("updated_by 为 %v，应被服务端改成提交者 %q", by, s.memberID)
			}
			if num(syncOf(f)["seq"]) != float64(mainSeq) {
				return fmt.Errorf("sync.seq 为 %v，应为 %d", syncOf(f)["seq"], mainSeq)
			}
			return nil
		}},
		{section: "§4.3", name: "版本号大的胜出，旧版本提交返回 stale 和当前版本", workspace: true, run: func() error {
			if _, _, err := s.pushOne(s.leader, feature(main, 2, t0.Add(time.Minute), map[string]any{"kind": "geo_point", "title": "D001", "note": "组长改过"}), "applied"); err != nil {
				return fmt.Errorf("组长提交第 2 版：%w", err)
			}
			_, cur, err := s.pushOne(s.member, feature(main, 1, t0.Add(2*time.Minute), map[string]any{"kind": "geo_point", "title": "D001", "note": "组员离线改的"}), "stale")
			if err != nil {
				return fmt.Errorf("组员提交第 1 版：%w", err)
			}
			if cur == nil || num(syncOf(cur)["version"]) != 2 {
				return fmt.Errorf("stale 的 current 应为第 2 版：%v", cur)
			}
			return nil
		}},
		{section: "§4.3", name: "版本号相同时 updated_at 晚的胜出", workspace: true, run: func() error {
			if _, _, err := s.pushOne(s.member, feature(main, 2, t0, map[string]any{"note": "更早的第 2 版"}), "stale"); err != nil {
				return fmt.Errorf("更早的同版本：%w", err)
			}
			if _, _, err := s.pushOne(s.member, feature(main, 2, t0.Add(3*time.Minute), map[string]any{"kind": "geo_point", "title": "D001", "note": "更晚的第 2 版"}), "applied"); err != nil {
				return fmt.Errorf("更晚的同版本：%w", err)
			}
			return nil
		}},
		{section: "§4.3", name: "被覆盖的版本留在历史里，新的在前", needs: "history", workspace: true, run: func() error {
			r, err := s.c.do(http.MethodGet, wsPath(s.ws, "/features/"+main+"/history"), s.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			var hs []map[string]any
			if err := r.json(&hs); err != nil {
				return err
			}
			var notes []any
			for _, h := range hs {
				p, _ := h["properties"].(map[string]any)
				notes = append(notes, p["note"])
			}
			want := []any{"组长改过", "灰岩，层理清晰 🪨"}
			if !reflect.DeepEqual(notes, want) {
				return fmt.Errorf("历史的 note 依次为 %v，应为 %v", notes, want)
			}
			return nil
		}},
		{section: "§4.4", name: "分页拉取：按 seq 升序、每个要素只返回最新版本、more 为 false 时结束", workspace: true, run: func() error {
			var fs []map[string]any
			for i := 0; i < 5; i++ {
				fs = append(fs, feature(randomID(), 1, t0, map[string]any{"kind": "note", "title": fmt.Sprintf("N%d", i)}))
			}
			if _, _, err := s.push(s.member, fs...); err != nil {
				return err
			}
			all, last, err := s.pullAll(s.member, 0, 2)
			if err != nil {
				return err
			}
			seen := map[string]bool{}
			prev := 0.0
			for _, f := range all {
				id, _ := f["id"].(string)
				if seen[id] {
					return fmt.Errorf("要素 %s 返回了不止一次", id)
				}
				seen[id] = true
				q := num(syncOf(f)["seq"])
				if q <= prev {
					return fmt.Errorf("seq 没有升序：%v 在 %v 之后", q, prev)
				}
				prev = q
			}
			if len(all) != 6 {
				return fmt.Errorf("共拉到 %d 个要素，应为 6", len(all))
			}
			if m := find(all, main); m == nil || num(syncOf(m)["version"]) != 2 {
				return errors.New("编辑过的要素不是最新版本")
			}
			again, err := s.pull(s.member, last, 0)
			if err != nil {
				return err
			}
			if len(again.Features) != 0 || again.Seq != last || again.More {
				return fmt.Errorf("从最新 seq 再拉应为空且 seq 不变，得到 %d 个、seq %d", len(again.Features), again.Seq)
			}
			return nil
		}},
		{section: "§4.4", name: "删除是一次修改，拉取时带 deleted: true", workspace: true, run: func() error {
			f := feature(main, 3, t0.Add(4*time.Minute), map[string]any{"kind": "geo_point", "title": "D001"})
			syncOf(f)["deleted"] = true
			seq, _, err := s.pushOne(s.member, f, "applied")
			if err != nil {
				return err
			}
			p, err := s.pull(s.leader, seq-1, 0)
			if err != nil {
				return err
			}
			g := find(p.Features, main)
			if g == nil || syncOf(g)["deleted"] != true {
				return errors.New("拉取结果里没有带 deleted: true 的要素")
			}
			if g["geometry"] == nil {
				return errors.New("删除后服务端清空了 geometry，客户端无法恢复")
			}
			if props, _ := g["properties"].(map[string]any); props["title"] != "D001" {
				return fmt.Errorf("删除后 properties 应原样保留，得到 %v", g["properties"])
			}
			return nil
		}},
		{section: "§4.4", name: "已删除的要素可以用更高版本恢复", workspace: true, run: func() error {
			f := feature(main, 4, t0.Add(5*time.Minute), map[string]any{"kind": "geo_point", "title": "D001"})
			seq, _, err := s.pushOne(s.leader, f, "applied")
			if err != nil {
				return err
			}
			p, err := s.pull(s.member, seq-1, 0)
			if err != nil {
				return err
			}
			g := find(p.Features, main)
			if g == nil || syncOf(g)["deleted"] == true {
				return errors.New("恢复后拉取结果里应是未删除的要素")
			}
			return nil
		}},
		{section: "§2.1", name: "只读成员不能提交，但能拉取", needs: "roles", workspace: true, run: func() error {
			if err := s.setRole(s.memberID, "readonly"); err != nil {
				return err
			}
			defer s.setRole(s.memberID, "member")
			r, err := s.c.do(http.MethodPost, wsPath(s.ws, "/features"), s.member, map[string]any{"features": []any{feature(randomID(), 1, t0, map[string]any{})}}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusForbidden, "forbidden"); err != nil {
				return err
			}
			_, err = s.pull(s.member, 0, 0)
			return err
		}},
		{section: "§2.1", name: "组员不能改角色", needs: "roles", workspace: true, run: func() error {
			r, err := s.c.do(http.MethodPatch, wsPath(s.ws, "/members/"+s.leaderID), s.member, map[string]any{"role": "member"}, nil)
			if err != nil {
				return err
			}
			return expect(r, http.StatusForbidden, "forbidden")
		}},
		{section: "§2.1", name: "不能去掉最后一位组长（409 last_leader）", needs: "roles", workspace: true, run: func() error {
			r, err := s.c.do(http.MethodPatch, wsPath(s.ws, "/members/"+s.leaderID), s.leader, map[string]any{"role": "member"}, nil)
			if err != nil {
				return err
			}
			return expect(r, http.StatusConflict, "last_leader")
		}},
		{section: "§2.2", name: "共享设置原样保存，版本加 1，旧版本修改返回 409 stale", needs: "settings", workspace: true, run: func() error {
			get := func() (int, map[string]any, error) {
				r, err := s.c.do(http.MethodGet, wsPath(s.ws, "/settings"), s.member, nil, nil)
				if err != nil {
					return 0, nil, err
				}
				if err := expect(r, http.StatusOK, ""); err != nil {
					return 0, nil, err
				}
				var v struct {
					Version  int            `json:"version"`
					Settings map[string]any `json:"settings"`
				}
				return v.Version, v.Settings, r.json(&v)
			}
			v0, _, err := get()
			if err != nil {
				return err
			}
			want := map[string]any{"x-conformance": map[string]any{"列表": []any{1.0, "二", true}}}
			r, err := s.c.do(http.MethodPut, wsPath(s.ws, "/settings"), s.leader, map[string]any{"settings": want, "base_version": v0}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			v1, got, err := get()
			if err != nil {
				return err
			}
			if v1 != v0+1 || !reflect.DeepEqual(got, want) {
				return fmt.Errorf("读回版本 %d、内容 %v，应为 %d、%v", v1, got, v0+1, want)
			}
			r, err = s.c.do(http.MethodPut, wsPath(s.ws, "/settings"), s.leader, map[string]any{"settings": map[string]any{}, "base_version": v0}, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusConflict, "stale"); err != nil {
				return err
			}
			r, err = s.c.do(http.MethodPut, wsPath(s.ws, "/settings"), s.member, map[string]any{"settings": map[string]any{}}, nil)
			if err != nil {
				return err
			}
			return expect(r, http.StatusForbidden, "forbidden")
		}},
		{section: "§5", name: "附件按 SHA-256 存取：201、重传 204、HEAD、哈希不符、Range", needs: "files", workspace: true, run: func() error {
			data := []byte("GeoCrew conformance attachment " + randomID())
			sum := sha256.Sum256(data)
			hash := hex.EncodeToString(sum[:])
			path := wsPath(s.ws, "/files/"+hash)
			typ := map[string]string{"Content-Type": "text/plain"}
			r, err := s.c.do(http.MethodPut, path, s.member, data, typ)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusCreated, ""); err != nil {
				return fmt.Errorf("首次上传：%w", err)
			}
			if r, err = s.c.do(http.MethodPut, path, s.member, data, typ); err != nil {
				return err
			} else if err := expect(r, http.StatusNoContent, ""); err != nil {
				return fmt.Errorf("重传：%w", err)
			}
			if r, err = s.c.do(http.MethodHead, path, s.leader, nil, nil); err != nil {
				return err
			} else if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("HEAD：%w", err)
			}
			missing := strings.Repeat("0", 64)
			if r, err = s.c.do(http.MethodHead, wsPath(s.ws, "/files/"+missing), s.leader, nil, nil); err != nil {
				return err
			} else if r.status != http.StatusNotFound {
				return fmt.Errorf("HEAD 不存在的附件：HTTP %d，应为 404", r.status)
			}
			if r, err = s.c.do(http.MethodPut, wsPath(s.ws, "/files/"+missing), s.member, data, typ); err != nil {
				return err
			} else if err := expect(r, http.StatusBadRequest, "hash_mismatch"); err != nil {
				return fmt.Errorf("哈希不符：%w", err)
			}
			if r, err = s.c.do(http.MethodGet, path, s.leader, nil, map[string]string{"Range": "bytes=0-7"}); err != nil {
				return err
			} else if err := expect(r, http.StatusPartialContent, ""); err != nil {
				return fmt.Errorf("Range：%w", err)
			} else if !bytes.Equal(r.body, data[:8]) {
				return fmt.Errorf("Range 取回 %q，应为 %q", r.body, data[:8])
			}
			return nil
		}},
		{section: "§7", name: "实时通道：hello、位置转发、要素变更通知", needs: "realtime", workspace: true, run: func() error {
			lead, err := dialWS(s.c.base, wsPath(s.ws, "/ws"), s.leader)
			if err != nil {
				return fmt.Errorf("组长连接：%w", err)
			}
			defer lead.Close()
			hello, err := lead.waitFor("hello", 5*time.Second, nil)
			if err != nil {
				return err
			}
			if hello["member_id"] != s.leaderID {
				return fmt.Errorf("hello 的 member_id 为 %v，应为 %q", hello["member_id"], s.leaderID)
			}
			if _, ok := hello["positions"].([]any); !ok {
				return errors.New("hello 缺少 positions 数组")
			}
			mem, err := dialWS(s.c.base, wsPath(s.ws, "/ws"), s.member)
			if err != nil {
				return fmt.Errorf("组员连接：%w", err)
			}
			defer mem.Close()
			if _, err := mem.waitFor("hello", 5*time.Second, nil); err != nil {
				return err
			}
			if err := mem.send(map[string]any{"type": "position", "lon": 116.4, "lat": 39.9, "accuracy": 5}); err != nil {
				return err
			}
			pos, err := lead.waitFor("position", 5*time.Second, func(m map[string]any) bool { return m["member_id"] == s.memberID })
			if err != nil {
				return fmt.Errorf("组长没收到组员的位置：%w", err)
			}
			if pos["nickname"] != s.memberNk || num(pos["lat"]) != 39.9 {
				return fmt.Errorf("转发的位置不对：%v", pos)
			}
			seq, _, err := s.pushOne(s.member, feature(randomID(), 1, t0, map[string]any{"kind": "note"}), "applied")
			if err != nil {
				return err
			}
			if _, err := lead.waitFor("feature_changed", 5*time.Second, func(m map[string]any) bool { return num(m["seq"]) >= float64(seq) }); err != nil {
				return err
			}
			return nil
		}},
	}
	tail := []check{
		{section: "§2", name: "重置邀请码后旧码失效", workspace: true, run: func() error {
			r, err := s.c.do(http.MethodPost, wsPath(s.ws, "/invite"), s.leader, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return err
			}
			var v struct {
				InviteCode string `json:"invite_code"`
			}
			if err := r.json(&v); err != nil {
				return err
			}
			if v.InviteCode == "" || strings.EqualFold(v.InviteCode, s.invite) {
				return fmt.Errorf("新邀请码 %q 应和旧的不同", v.InviteCode)
			}
			r, err = s.c.do(http.MethodPost, "/api/v0/workspaces/join", "", map[string]any{"invite_code": s.invite, "nickname": "迟到的人"}, nil)
			if err != nil {
				return err
			}
			return expect(r, http.StatusNotFound, "bad_invite")
		}},
		{section: "§2", name: "吊销成员后其令牌立即失效", workspace: true, run: func() error {
			r, err := s.c.do(http.MethodDelete, wsPath(s.ws, "/members/"+s.memberID), s.leader, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusNoContent, ""); err != nil {
				return err
			}
			r, err = s.c.do(http.MethodGet, wsPath(s.ws, "/features?since=0"), s.member, nil, nil)
			if err != nil {
				return err
			}
			return expect(r, http.StatusUnauthorized, "unauthorized")
		}},
	}
	checks := append(core, s.messageChecks()...)
	checks = append(checks, s.exportChecks()...)
	checks = append(checks, s.mcpChecks()...)
	checks = append(checks, s.archiveChecks()...)
	checks = append(checks, s.v1Checks()...)
	checks = append(checks, s.v1RowChecks()...)
	checks = append(checks, s.v1RoleChecks()...)
	checks = append(checks, s.v1MemberChecks()...)
	checks = append(checks, s.v1FileChecks()...)
	return append(checks, tail...)
}

type joinResult struct {
	WorkspaceID   string `json:"workspace_id"`
	WorkspaceName string `json:"workspace_name"`
	MemberID      string `json:"member_id"`
	Role          string `json:"role"`
	Token         string `json:"token"`
	InviteCode    string `json:"invite_code"`
}

func (j joinResult) valid(role string) error {
	switch {
	case j.WorkspaceID == "" || j.MemberID == "" || j.Token == "" || j.WorkspaceName == "":
		return fmt.Errorf("缺少 workspace_id、workspace_name、member_id 或 token：%+v", j)
	case j.Role != role:
		return fmt.Errorf("role 为 %q，应为 %q", j.Role, role)
	}
	return nil
}

func (s *suite) setRole(member, role string) error {
	r, err := s.c.do(http.MethodPatch, wsPath(s.ws, "/members/"+member), s.leader, map[string]any{"role": role}, nil)
	if err != nil {
		return err
	}
	if r.status != http.StatusNoContent && r.status != http.StatusOK {
		return fmt.Errorf("改角色返回 HTTP %d：%s", r.status, clip(r.body))
	}
	return nil
}

func find(fs []map[string]any, id string) map[string]any {
	for _, f := range fs {
		if f["id"] == id {
			return f
		}
	}
	return nil
}

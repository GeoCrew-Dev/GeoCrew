package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"time"
)

type v1Upload struct {
	Upload    string `json:"upload"`
	Offset    int64  `json:"offset"`
	ChunkSize int64  `json:"chunk_size"`
	Exists    bool   `json:"exists"`
	SHA256    string `json:"sha256"`
}

// v1FileChecks cover attachments' files (v1 9.2): an upload by chunks that
// can be picked up again, and downloads, in the workspace v1Checks made.
func (s *suite) v1FileChecks() []check {
	file := []byte(fmt.Sprintf("GeoCrew conformance %s: a photo's bytes", randomID()))
	sha, size := sha256Hex(file), int64(len(file))
	layer, row := randomID(), randomID()
	var upload string
	ready := func() error {
		if s.v1 == nil || s.v1.member == "" {
			return skipped("没有建好 v1 的测试工作区")
		}
		return nil
	}
	start := func(token, hash string, n int64) (v1Upload, error) {
		r, err := s.c.do(http.MethodPost, v1Path(s.v1.ws, "/uploads"), token, map[string]any{"sha256": hash, "size": n, "type": "image/jpeg"}, nil)
		if err != nil {
			return v1Upload{}, err
		}
		if err := expect(r, http.StatusOK, ""); err != nil {
			return v1Upload{}, err
		}
		var u v1Upload
		return u, r.json(&u)
	}
	put := func(id string, offset int64, b []byte) (response, error) {
		return s.c.do(http.MethodPut, v1Path(s.v1.ws, fmt.Sprintf("/uploads/%s?offset=%d", id, offset)), s.v1.leader, b, nil)
	}
	return []check{
		{section: "v1 §9.2", name: "开始上传给出上传 ID 和 chunk_size；偏移不对返回 409 offset_mismatch 带正确的 offset；再开始一次从断点接着传", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			u, err := start(s.v1.leader, sha, size)
			if err != nil {
				return err
			}
			if u.Upload == "" || u.Offset != 0 || u.ChunkSize < size {
				return fmt.Errorf("开始上传应给出 upload、offset 0 和 chunk_size：%+v", u)
			}
			upload = u.Upload
			r, err := put(upload, 5, file[5:10])
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusConflict, "offset_mismatch"); err != nil {
				return fmt.Errorf("偏移不对：%w", err)
			}
			var at struct {
				Offset *int64 `json:"offset"`
			}
			if err := r.json(&at); err != nil || at.Offset == nil || *at.Offset != 0 {
				return fmt.Errorf("offset_mismatch 应带上正确的 offset 0：%s", clip(r.body))
			}
			if r, err = put(upload, 0, file[:10]); err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("第一块：%w", err)
			}
			again, err := start(s.v1.leader, sha, size)
			if err != nil {
				return err
			}
			if again.Upload != upload || again.Offset != 10 {
				return fmt.Errorf("再开始一次应接着原来的上传，offset 10：%+v", again)
			}
			return nil
		}},
		{section: "v1 §9.2", name: "没有附件行引用的文件下载返回 404；引用了、没传完返回 404 not_uploaded；最后一块返回 201，之后能下载，支持 Range", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			if upload == "" {
				return skipped("上一条检查没有开始上传")
			}
			r, err := s.c.do(http.MethodGet, v1Path(s.v1.ws, "/files/"+sha), s.v1.member, nil, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusNotFound, ""); err != nil {
				return fmt.Errorf("没有附件行引用：%w", err)
			}
			body := map[string]any{"id": layer, "name": "附件检查", "geometry_type": "Point", "crs": "EPSG:4490",
				"fields": []any{map[string]any{"id": "f1", "name": "点名", "type": "string"}}}
			if r, err = s.c.do(http.MethodPost, v1Path(s.v1.ws, "/layers"), s.v1.leader, body, nil); err != nil {
				return err
			}
			if r.status != http.StatusOK && r.status != http.StatusCreated {
				return fmt.Errorf("建图层：HTTP %d %s", r.status, clip(r.body))
			}
			at := nowish().Add(-time.Minute).Format(time.RFC3339)
			edit := func(v any) map[string]any { return map[string]any{"value": v, "edited_at": at} }
			changes := []any{
				map[string]any{"id": randomID(), "layer": layer, "row": row, "base_seq": 0, "set": map[string]any{"f1": edit("D001")}},
				map[string]any{"id": randomID(), "layer": "attachments", "row": randomID(), "base_seq": 0, "set": map[string]any{
					"target_layer": edit(layer), "target_row": edit(row), "file": edit(sha), "type": edit("image/jpeg"), "size": edit(size), "name": edit("D001.jpg")}},
			}
			if r, err = s.c.do(http.MethodPost, v1Path(s.v1.ws, "/rows"), s.v1.leader, map[string]any{"client_now": nowish().Format(time.RFC3339), "changes": changes}, nil); err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("提交附件行：%w", err)
			}
			if r, err = s.c.do(http.MethodGet, v1Path(s.v1.ws, "/files/"+sha), s.v1.member, nil, nil); err != nil {
				return err
			}
			if err := expect(r, http.StatusNotFound, "not_uploaded"); err != nil {
				return fmt.Errorf("引用了、没传完：%w", err)
			}
			if r, err = put(upload, 10, file[10:]); err != nil {
				return err
			}
			if err := expect(r, http.StatusCreated, ""); err != nil {
				return fmt.Errorf("最后一块：%w", err)
			}
			var done v1Upload
			if err := r.json(&done); err != nil || done.SHA256 != sha {
				return fmt.Errorf("最后一块应返回文件的 sha256：%s", clip(r.body))
			}
			if r, err = s.c.do(http.MethodGet, v1Path(s.v1.ws, "/files/"+sha), s.v1.member, nil, nil); err != nil {
				return err
			}
			if err := expect(r, http.StatusOK, ""); err != nil {
				return fmt.Errorf("下载：%w", err)
			}
			if !bytes.Equal(r.body, file) {
				return fmt.Errorf("下载的内容和上传的不一样：%s", clip(r.body))
			}
			if r, err = s.c.do(http.MethodGet, v1Path(s.v1.ws, "/files/"+sha), s.v1.member, nil, map[string]string{"Range": "bytes=5-9"}); err != nil {
				return err
			}
			if err := expect(r, http.StatusPartialContent, ""); err != nil {
				return fmt.Errorf("Range：%w", err)
			}
			if !bytes.Equal(r.body, file[5:10]) {
				return fmt.Errorf("Range 取到的内容不对：%q", r.body)
			}
			return nil
		}},
		{section: "v1 §9.2", name: "已有的文件开始上传返回 exists；传完的上传再传返回 404；内容和哈希对不上返回 400 hash_mismatch", v1: true, run: func() error {
			if err := ready(); err != nil {
				return err
			}
			if upload == "" {
				return skipped("前面的检查没有开始上传")
			}
			u, err := start(s.v1.member, sha, size)
			if err != nil {
				return err
			}
			if !u.Exists {
				return fmt.Errorf("文件已经有了，应返回 exists：%+v", u)
			}
			r, err := put(upload, size, nil)
			if err != nil {
				return err
			}
			if err := expect(r, http.StatusNotFound, ""); err != nil {
				return fmt.Errorf("传完的上传再传：%w", err)
			}
			other := []byte("not what the hash names " + randomID())
			bad, err := start(s.v1.leader, sha256Hex([]byte("something else "+randomID())), int64(len(other)))
			if err != nil {
				return err
			}
			if r, err = put(bad.Upload, 0, other); err != nil {
				return err
			}
			return expect(r, http.StatusBadRequest, "hash_mismatch")
		}},
	}
}

func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

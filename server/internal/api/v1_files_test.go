package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"strconv"
	"testing"
	"time"

	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

type v1UploadResp struct {
	Upload    string `json:"upload"`
	Offset    int64  `json:"offset"`
	ChunkSize int64  `json:"chunk_size"`
	Exists    bool   `json:"exists"`
	SHA256    string `json:"sha256"`
	Error     string `json:"error"`
}

func hashOf(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

// fetch GETs (or HEADs) path with the token and an optional Range, for the status, the body and its length header.
func (e *env) fetch(method, path, token, rng string) (int, []byte) {
	e.t.Helper()
	req, _ := http.NewRequest(method, e.srv.URL+path, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	if rng != "" {
		req.Header.Set("Range", rng)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, b
}

func TestV1UploadByChunksAndDownload(t *testing.T) {
	e := newEnv(t)
	var leader, member v1JoinedResp
	e.call("POST", "/api/v1/workspaces", "", map[string]string{"name": "附件", "nickname": "组长", "device": "a"}, &leader)
	e.call("POST", "/api/v1/join", "", map[string]string{"invite_code": leader.Workspace.InviteCode, "nickname": "组员", "device": "b"}, &member)
	ws := "/api/v1/workspaces/" + leader.Workspace.ID
	layer := map[string]any{"id": v1LayerID, "name": "野外点", "geometry_type": "Point", "crs": "EPSG:4490",
		"fields": []map[string]any{{"id": "f1", "name": "点名", "type": "string"}}}
	if c := e.call("POST", ws+"/layers", leader.Token, layer, nil); c != 200 {
		t.Fatalf("layer: %d", c)
	}

	photo := bytes.Repeat([]byte("0123456789abcdef"), v1.ChunkSize/16+1) // a chunk and 16 bytes
	sha, size := hashOf(photo), int64(len(photo))
	var up v1UploadResp
	if c := e.call("POST", ws+"/uploads", member.Token, map[string]any{"sha256": sha, "size": size, "type": "image/jpeg"}, &up); c != 200 || up.Upload == "" || up.Offset != 0 || up.ChunkSize != v1.ChunkSize {
		t.Fatalf("start: %d %+v", c, up)
	}
	put := func(offset int64, body []byte, out *v1UploadResp) int {
		return e.call("PUT", ws+"/uploads/"+up.Upload+"?offset="+strconv.FormatInt(offset, 10), member.Token, body, out)
	}
	var got v1UploadResp
	if c := put(5, photo[5:10], &got); c != 409 || got.Error != "offset_mismatch" || got.Offset != 0 {
		t.Fatalf("not where it stands: %d %+v", c, got)
	}
	if c := put(0, photo[:v1.ChunkSize+1], &got); c != 400 || got.Error != "invalid" {
		t.Fatalf("over chunk_size: %d %+v", c, got)
	}
	got = v1UploadResp{}
	if c := put(0, photo[:v1.ChunkSize], &got); c != 200 || got.Offset != v1.ChunkSize {
		t.Fatalf("first chunk: %d %+v", c, got)
	}
	// After the app restarted: starting again goes on from there.
	var again v1UploadResp
	if c := e.call("POST", ws+"/uploads", member.Token, map[string]any{"sha256": sha, "size": size, "type": "image/jpeg"}, &again); c != 200 || again.Upload != up.Upload || again.Offset != v1.ChunkSize {
		t.Fatalf("start again: %d %+v", c, again)
	}

	// Nobody may fetch it before an attachment row refers to it; then it is "not uploaded" until it is.
	if c, _ := e.fetch("GET", ws+"/files/"+sha, leader.Token, ""); c != 404 {
		t.Fatalf("no row refers to it: %d", c)
	}
	now := time.Now().UTC().Format(time.RFC3339)
	edit := func(v any) map[string]any { return map[string]any{"value": v, "edited_at": now} }
	row := map[string]any{"id": "0b6f1c3e-5d2a-4e8b-9f10-2a3b4c5d6e7f", "layer": v1LayerID, "row": v1RowID, "base_seq": 0, "set": map[string]any{"f1": edit("D001")}}
	attachment := map[string]any{"id": "1c7a2d4f-6e3b-4f9c-8a21-3b4c5d6e7f80", "layer": "attachments", "row": "2d8b3e5a-7f4c-4a0d-9b32-4c5d6e7f8091", "base_seq": 0,
		"set": map[string]any{"target_layer": edit(v1LayerID), "target_row": edit(v1RowID), "file": edit(sha), "type": edit("image/jpeg"), "size": edit(size), "name": edit("D001.jpg")}}
	var pushed v1PushResp
	if c := e.call("POST", ws+"/rows", member.Token, map[string]any{"client_now": now, "changes": []any{row, attachment}}, &pushed); c != 200 || pushed.Results[1].Status != "applied" {
		t.Fatalf("attachment row: %d %+v", c, pushed)
	}
	var fail v1Error
	if c := e.call("GET", ws+"/files/"+sha, leader.Token, nil, &fail); c != 404 || fail.Error != "not_uploaded" {
		t.Fatalf("referred to, not uploaded: %d %+v", c, fail)
	}

	got = v1UploadResp{}
	if c := put(v1.ChunkSize, photo[v1.ChunkSize:], &got); c != 201 || got.SHA256 != sha {
		t.Fatalf("last chunk: %d %+v", c, got)
	}
	if c := put(size, nil, &got); c != 404 {
		t.Fatalf("a finished upload takes no more: %d", c)
	}
	if c := e.call("POST", ws+"/uploads", leader.Token, map[string]any{"sha256": sha, "size": size, "type": "image/jpeg"}, &again); c != 200 || !again.Exists {
		t.Fatalf("the file is here: %d %+v", c, again)
	}
	if c, b := e.fetch("GET", ws+"/files/"+sha, leader.Token, ""); c != 200 || !bytes.Equal(b, photo) {
		t.Fatalf("download: %d, %d bytes", c, len(b))
	}
	if c, b := e.fetch("GET", ws+"/files/"+sha, leader.Token, "bytes=16-31"); c != 206 || !bytes.Equal(b, photo[16:32]) {
		t.Fatalf("a range, to go on from where a download stopped: %d %q", c, b)
	}
	if c, b := e.fetch("HEAD", ws+"/files/"+sha, leader.Token, ""); c != 200 || len(b) != 0 {
		t.Fatalf("head: %d %d", c, len(b))
	}

	// Bytes that are not the file named void the upload.
	wrong := []byte("xyz")
	var bad v1UploadResp
	e.call("POST", ws+"/uploads", member.Token, map[string]any{"sha256": hashOf([]byte("abc")), "size": 3, "type": "text/plain"}, &bad)
	if c := e.call("PUT", ws+"/uploads/"+bad.Upload+"?offset=0", member.Token, wrong, &got); c != 400 || got.Error != "hash_mismatch" {
		t.Fatalf("hash mismatch: %d %+v", c, got)
	}
	if c := e.call("PUT", ws+"/uploads/"+bad.Upload+"?offset=0", member.Token, []byte("abc"), nil); c != 404 {
		t.Fatalf("a void upload takes no more: %d", c)
	}
	// Someone else's upload is not theirs to fill.
	var mine v1UploadResp
	e.call("POST", ws+"/uploads", member.Token, map[string]any{"sha256": hashOf([]byte("def")), "size": 3, "type": "text/plain"}, &mine)
	if c := e.call("PUT", ws+"/uploads/"+mine.Upload+"?offset=0", leader.Token, []byte("def"), nil); c != 404 {
		t.Fatalf("another member's upload: %d", c)
	}
}

func TestV1StaleUploadsAreDropped(t *testing.T) {
	var srv *Server
	e := newEnvWith(t, func(s *Server) { srv = s })
	var leader v1JoinedResp
	e.call("POST", "/api/v1/workspaces", "", map[string]string{"name": "附件", "nickname": "组长", "device": "a"}, &leader)
	ws := "/api/v1/workspaces/" + leader.Workspace.ID
	var up v1UploadResp
	e.call("POST", ws+"/uploads", leader.Token, map[string]any{"sha256": hashOf([]byte("abcd")), "size": 4, "type": "text/plain"}, &up)
	if c := e.call("PUT", ws+"/uploads/"+up.Upload+"?offset=0", leader.Token, []byte("ab"), nil); c != 200 {
		t.Fatalf("a part: %d", c)
	}
	if err := srv.cleanUploads(context.Background(), time.Now()); err != nil {
		t.Fatal(err)
	}
	if c := e.call("PUT", ws+"/uploads/"+up.Upload+"?offset=2", leader.Token, []byte("c"), nil); c != 200 {
		t.Fatalf("a fresh upload stays: %d", c)
	}
	if err := srv.cleanUploads(context.Background(), time.Now().Add(v1.UploadExpiry+time.Hour)); err != nil {
		t.Fatal(err)
	}
	if c := e.call("PUT", ws+"/uploads/"+up.Upload+"?offset=3", leader.Token, []byte("d"), nil); c != 404 {
		t.Fatalf("one untouched for a week is gone: %d", c)
	}
}

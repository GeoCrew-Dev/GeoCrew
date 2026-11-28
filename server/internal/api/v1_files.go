package api

import (
	"context"
	"errors"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

// v1FileRoutes serves attachments' files (protocol v1 9.2): uploads by
// chunks that can be picked up again, and downloads by hash.
func (s *Server) v1FileRoutes(mux *http.ServeMux) {
	ws := "/api/v1/workspaces/{ws}"
	mux.Handle("POST "+ws+"/uploads", s.v1Auth(s.v1StartUpload))
	mux.Handle("PUT "+ws+"/uploads/{upload}", s.v1Auth(s.v1PutChunk))
	// GET serves HEAD as well: the same rules, no body.
	mux.Handle("GET "+ws+"/files/{sha}", s.v1Auth(s.v1GetFile))
}

func (s *Server) v1StartUpload(w http.ResponseWriter, r *http.Request) {
	var req struct {
		SHA256 string `json:"sha256"`
		Size   *int64 `json:"size"`
		Type   string `json:"type"`
	}
	if !decode(w, r, &req) {
		return
	}
	if req.Size == nil {
		writeErr(w, http.StatusBadRequest, "invalid", "size is required")
		return
	}
	u, exists, err := s.V1.StartUpload(r.Context(), r.PathValue("ws"), v1Who(r).Member.ID, req.SHA256, *req.Size, req.Type)
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	if exists {
		writeJSON(w, http.StatusOK, map[string]any{"exists": true})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"upload": u.ID, "offset": u.Offset, "chunk_size": v1.ChunkSize})
}

// uploading keeps one chunk of an upload at a time: two sent at once (a
// retry while the first is still on its way) would write over each other.
var uploading sync.Map // upload id -> *sync.Mutex

func (s *Server) v1PutChunk(w http.ResponseWriter, r *http.Request) {
	ctx, ws, id, actor := r.Context(), r.PathValue("ws"), r.PathValue("upload"), v1Who(r).Member.ID
	offset, err := strconv.ParseInt(r.URL.Query().Get("offset"), 10, 64)
	if err != nil || offset < 0 {
		writeErr(w, http.StatusBadRequest, "invalid", "offset must be the number of bytes the server has")
		return
	}
	lock, _ := uploading.LoadOrStore(id, &sync.Mutex{})
	lock.(*sync.Mutex).Lock()
	defer lock.(*sync.Mutex).Unlock()

	u, err := s.V1.Upload(ctx, ws, actor, id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			uploading.Delete(id) // no such upload: no lock kept for it
		}
		s.v1Fail(w, r, err)
		return
	}
	if offset != u.Offset {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "offset_mismatch", "message": "the chunk does not start where the upload stands", "offset": u.Offset})
		return
	}
	n, err := s.Files.Append(ws, id, offset, r.Body, min(v1.ChunkSize, u.Size-offset))
	switch {
	case errors.Is(err, blob.ErrTooLarge):
		writeErr(w, http.StatusBadRequest, "invalid", "the chunk is over chunk_size, or goes past the size the upload began with")
		return
	case err != nil:
		s.fail(w, r, err)
		return
	}
	got, err := s.V1.Received(ctx, ws, actor, id, offset, n)
	if errors.Is(err, v1.ErrOffsetMismatch) {
		writeJSON(w, http.StatusConflict, map[string]any{"error": "offset_mismatch", "message": "the chunk does not start where the upload stands", "offset": got})
		return
	}
	if err != nil {
		s.v1Fail(w, r, err)
		return
	}
	if got < u.Size {
		writeJSON(w, http.StatusOK, map[string]any{"offset": got})
		return
	}
	// All of it: the bytes must be the file the upload named.
	err = s.Files.Commit(ws, id, u.SHA256)
	if err != nil && !errors.Is(err, blob.ErrMismatch) {
		s.fail(w, r, err)
		return
	}
	if ferr := s.V1.FinishUpload(ctx, ws, actor, id, err == nil); ferr != nil {
		s.v1Fail(w, r, ferr)
		return
	}
	uploading.Delete(id)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "hash_mismatch", "the bytes are not the file the upload named; it is void")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"sha256": u.SHA256})
}

func (s *Server) v1GetFile(w http.ResponseWriter, r *http.Request) {
	ws, sha := r.PathValue("ws"), r.PathValue("sha")
	visible, uploaded, err := s.V1.FileAccess(r.Context(), ws, v1Who(r).Member.ID, sha)
	switch {
	case err != nil:
		s.v1Fail(w, r, err)
		return
	case !visible:
		writeErr(w, http.StatusNotFound, "not_found", "no attachment you can see refers to this file")
		return
	case !uploaded:
		writeErr(w, http.StatusNotFound, "not_uploaded", "the file has not been uploaded yet")
		return
	}
	f, err := s.Files.Open(ws, sha)
	if errors.Is(err, os.ErrNotExist) {
		s.Log.Error("a stored file is missing", "workspace", ws, "sha256", sha)
		s.fail(w, r, store.ErrNotFound)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	defer f.Close()
	h := w.Header()
	// Content-addressed: the bytes behind a hash never change. The type is sniffed; the attachment row has the real one.
	h.Set("Cache-Control", "private, max-age=31536000, immutable")
	h.Set("ETag", `"`+sha+`"`)
	http.ServeContent(w, r, "", time.Time{}, f)
}

// CleanUploads drops uploads nobody added to for v1.UploadExpiry (protocol
// v1 9.2), with what they had received, every [every] until ctx ends.
func (s *Server) CleanUploads(ctx context.Context, every time.Duration) {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		if err := s.cleanUploads(ctx, time.Now()); err != nil && ctx.Err() == nil {
			s.Log.Error("dropping stale uploads", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (s *Server) cleanUploads(ctx context.Context, now time.Time) error {
	stale, err := s.V1.ExpiredUploads(ctx, now.Add(-v1.UploadExpiry))
	if err != nil {
		return err
	}
	for _, u := range stale {
		if err := s.Files.Discard(u.WorkspaceID, u.ID); err != nil {
			return err
		}
		if err := s.V1.DropUpload(ctx, u.WorkspaceID, u.ID); err != nil {
			return err
		}
		uploading.Delete(u.ID)
	}
	return nil
}

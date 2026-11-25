package api

import (
	"errors"
	"mime"
	"net/http"
	"os"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
)

func (s *Server) putFile(w http.ResponseWriter, r *http.Request) {
	ws, sha := r.PathValue("ws"), r.PathValue("sha")
	if !blob.ValidHash(sha) {
		writeErr(w, http.StatusBadRequest, "bad_hash", "path must end with the lowercase hex sha256 of the body")
		return
	}
	if s.Files.MaxSize > 0 && r.ContentLength > s.Files.MaxSize {
		writeErr(w, http.StatusRequestEntityTooLarge, "too_large", "file exceeds max_upload_bytes")
		return
	}
	// Already stored: skip the body so clients can blindly retry uploads.
	if _, err := s.Store.FileMeta(r.Context(), ws, sha); err == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	n, err := s.Files.Put(ws, sha, r.Body)
	switch {
	case errors.Is(err, blob.ErrTooLarge):
		writeErr(w, http.StatusRequestEntityTooLarge, "too_large", "file exceeds max_upload_bytes")
		return
	case errors.Is(err, blob.ErrMismatch):
		writeErr(w, http.StatusBadRequest, "hash_mismatch", "body does not match sha256")
		return
	case err != nil:
		s.fail(w, r, err)
		return
	}
	ct := r.Header.Get("Content-Type")
	if mt, _, err := mime.ParseMediaType(ct); err != nil || mt == "" {
		ct = "application/octet-stream"
	}
	meta := store.FileMeta{SHA256: sha, Size: n, ContentType: ct, CreatedAt: time.Now().UTC(), CreatedBy: member(r).ID}
	if err := s.Store.PutFileMeta(r.Context(), ws, meta); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusCreated)
}

func (s *Server) getFile(w http.ResponseWriter, r *http.Request) {
	ws, sha := r.PathValue("ws"), r.PathValue("sha")
	meta, err := s.Store.FileMeta(r.Context(), ws, sha)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	f, err := s.Files.Open(ws, sha)
	if errors.Is(err, os.ErrNotExist) {
		s.fail(w, r, store.ErrNotFound)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	defer f.Close()
	h := w.Header()
	h.Set("Content-Type", meta.ContentType)
	// Content-addressed: the bytes behind a hash never change.
	h.Set("Cache-Control", "private, max-age=31536000, immutable")
	h.Set("ETag", `"`+sha+`"`)
	http.ServeContent(w, r, "", meta.CreatedAt, f)
}

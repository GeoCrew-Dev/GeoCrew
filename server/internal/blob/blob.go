// Package blob stores attachments (photos, sketches, audio) on local disk,
// addressed by the SHA-256 of their content.
package blob

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
)

var (
	ErrBadHash  = errors.New("invalid sha256")
	ErrMismatch = errors.New("content does not match sha256")
	ErrTooLarge = errors.New("file too large")
)

var (
	hashRe = regexp.MustCompile(`^[0-9a-f]{64}$`)
	idRe   = regexp.MustCompile(`^[0-9a-f]{1,64}$`)
)

// ValidHash reports whether s is a lowercase hex SHA-256.
func ValidHash(s string) bool { return hashRe.MatchString(s) }

// Disk keeps files under Root/<workspace>/<aa>/<sha256>.
type Disk struct {
	Root    string
	MaxSize int64
}

func (d *Disk) path(workspaceID, sha string) string {
	return filepath.Join(d.Root, workspaceID, sha[:2], sha)
}

// Put streams r to disk, verifying size and hash, and returns the byte count.
// Writing an existing file is a cheap no-op apart from reading the body.
func (d *Disk) Put(workspaceID, sha string, r io.Reader) (int64, error) {
	if !ValidHash(sha) || !idRe.MatchString(workspaceID) {
		return 0, ErrBadHash
	}
	dst := d.path(workspaceID, sha)
	if err := os.MkdirAll(filepath.Dir(dst), 0o750); err != nil {
		return 0, err
	}
	tmp, err := os.CreateTemp(filepath.Dir(dst), ".upload-*")
	if err != nil {
		return 0, err
	}
	defer os.Remove(tmp.Name())
	h := sha256.New()
	limit := d.MaxSize
	if limit <= 0 {
		limit = 1 << 62
	}
	n, err := io.Copy(io.MultiWriter(tmp, h), io.LimitReader(r, limit+1))
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return 0, err
	}
	if n > limit {
		return 0, ErrTooLarge
	}
	if hex.EncodeToString(h.Sum(nil)) != sha {
		return 0, ErrMismatch
	}
	if err := os.Rename(tmp.Name(), dst); err != nil {
		return 0, err
	}
	return n, nil
}

// Open returns the stored file or an error satisfying errors.Is(err, os.ErrNotExist).
func (d *Disk) Open(workspaceID, sha string) (*os.File, error) {
	if !ValidHash(sha) || !idRe.MatchString(workspaceID) {
		return nil, os.ErrNotExist
	}
	return os.Open(d.path(workspaceID, sha))
}

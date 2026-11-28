// Package blob stores attachments (photos, sketches, audio) on local disk,
// addressed by the SHA-256 of their content.
package blob

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

var (
	ErrBadHash  = errors.New("invalid sha256")
	ErrMismatch = errors.New("content does not match sha256")
	ErrTooLarge = errors.New("file too large")
)

var (
	hashRe   = regexp.MustCompile(`^[0-9a-f]{64}$`)
	idRe     = regexp.MustCompile(`^[0-9a-f]{1,64}$`)
	uploadRe = regexp.MustCompile(`^u_[0-9a-f]{1,64}$`)
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

// part is where the bytes of upload so far are kept, out of the way of List.
func (d *Disk) part(workspaceID, upload string) string {
	return filepath.Join(d.Root, workspaceID, ".uploads", upload)
}

// Append writes what r holds, at most max bytes, at offset of upload's part
// file (protocol v1 9.2) and returns how many it wrote. Anything past offset
// from an earlier write that was never counted goes first, so a chunk sent
// again lands where it belongs. More than max bytes is ErrTooLarge, and the
// part is left as it was up to offset.
func (d *Disk) Append(workspaceID, upload string, offset int64, r io.Reader, max int64) (int64, error) {
	if !idRe.MatchString(workspaceID) || !uploadRe.MatchString(upload) || offset < 0 || max < 0 {
		return 0, ErrBadHash
	}
	p := d.part(workspaceID, upload)
	if err := os.MkdirAll(filepath.Dir(p), 0o750); err != nil {
		return 0, err
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_WRONLY, 0o640)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	if err := f.Truncate(offset); err != nil {
		return 0, err
	}
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		return 0, err
	}
	n, err := io.Copy(f, io.LimitReader(r, max+1))
	if err == nil && n > max {
		err = ErrTooLarge
	}
	if err != nil {
		if terr := f.Truncate(offset); terr != nil {
			return 0, terr
		}
		return 0, err
	}
	return n, f.Sync()
}

// Commit checks that upload's part file is the file sha names and, if so,
// stores it as that file. A part that is not is removed: ErrMismatch.
func (d *Disk) Commit(workspaceID, upload, sha string) error {
	if !idRe.MatchString(workspaceID) || !uploadRe.MatchString(upload) || !ValidHash(sha) {
		return ErrBadHash
	}
	p := d.part(workspaceID, upload)
	f, err := os.Open(p)
	if err != nil {
		return err
	}
	h := sha256.New()
	_, err = io.Copy(h, f)
	f.Close()
	if err != nil {
		return err
	}
	if hex.EncodeToString(h.Sum(nil)) != sha {
		os.Remove(p)
		return ErrMismatch
	}
	dst := d.path(workspaceID, sha)
	// Someone else's upload of the same file got there first: the bytes are the same, and the one there may be open.
	if _, err := os.Stat(dst); err == nil {
		return os.Remove(p)
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o750); err != nil {
		return err
	}
	return os.Rename(p, dst)
}

// Discard removes upload's part file; one already gone is not an error.
func (d *Disk) Discard(workspaceID, upload string) error {
	if !idRe.MatchString(workspaceID) || !uploadRe.MatchString(upload) {
		return ErrBadHash
	}
	if err := os.Remove(d.part(workspaceID, upload)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return nil
}

// Open returns the stored file or an error satisfying errors.Is(err, os.ErrNotExist).
func (d *Disk) Open(workspaceID, sha string) (*os.File, error) {
	if !ValidHash(sha) || !idRe.MatchString(workspaceID) {
		return nil, os.ErrNotExist
	}
	return os.Open(d.path(workspaceID, sha))
}

// Stored describes one file on disk.
type Stored struct {
	SHA     string
	Size    int64
	ModTime time.Time
}

// List returns the workspace's stored files; leftover temporary uploads
// and anything not named by a hash are skipped. A workspace with no files
// yields an empty list.
func (d *Disk) List(workspaceID string) ([]Stored, error) {
	if !idRe.MatchString(workspaceID) {
		return nil, ErrBadHash
	}
	root := filepath.Join(d.Root, workspaceID)
	var out []Stored
	err := filepath.WalkDir(root, func(path string, e fs.DirEntry, err error) error {
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) && path == root {
				return filepath.SkipDir
			}
			return err
		}
		if e.IsDir() || !ValidHash(e.Name()) || filepath.Base(filepath.Dir(path)) != e.Name()[:2] {
			return nil
		}
		info, err := e.Info()
		if err != nil {
			return err
		}
		out = append(out, Stored{SHA: e.Name(), Size: info.Size(), ModTime: info.ModTime()})
		return nil
	})
	return out, err
}

// Remove deletes a stored file; a file that is already gone is not an error.
func (d *Disk) Remove(workspaceID, sha string) error {
	if !ValidHash(sha) || !idRe.MatchString(workspaceID) {
		return ErrBadHash
	}
	if err := os.Remove(d.path(workspaceID, sha)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return nil
}

// RemoveWorkspace deletes every file of the workspace; a workspace with no
// files is not an error.
func (d *Disk) RemoveWorkspace(workspaceID string) error {
	if !idRe.MatchString(workspaceID) {
		return ErrBadHash
	}
	return os.RemoveAll(filepath.Join(d.Root, workspaceID))
}

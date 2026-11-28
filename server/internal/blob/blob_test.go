package blob

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"strings"
	"testing"
)

func sum(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func TestPutOpen(t *testing.T) {
	d := &Disk{Root: t.TempDir(), MaxSize: 10}
	if n, err := d.Put("ab12", sum("hello"), strings.NewReader("hello")); err != nil || n != 5 {
		t.Fatalf("put: %v %d", err, n)
	}
	f, err := d.Open("ab12", sum("hello"))
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(f)
	f.Close()
	if string(b) != "hello" {
		t.Fatalf("got %q", b)
	}
	if _, err := d.Put("ab12", sum("x"), strings.NewReader("y")); !errors.Is(err, ErrMismatch) {
		t.Fatalf("mismatch: %v", err)
	}
	big := "0123456789A"
	if _, err := d.Put("ab12", sum(big), strings.NewReader(big)); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("too large: %v", err)
	}
	if _, err := d.Put("../x", sum("hello"), strings.NewReader("hello")); !errors.Is(err, ErrBadHash) {
		t.Fatalf("traversal: %v", err)
	}
	if _, err := d.Open("ab12", sum("nope")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("missing: %v", err)
	}
}

func TestUploadByChunks(t *testing.T) {
	d := &Disk{Root: t.TempDir()}
	whole := "hello, field"
	if n, err := d.Append("ab12", "u_01", 0, strings.NewReader("hello"), 5); err != nil || n != 5 {
		t.Fatalf("first chunk: %v %d", err, n)
	}
	// A chunk over what it may be leaves the part as it was.
	if _, err := d.Append("ab12", "u_01", 5, strings.NewReader(", field!"), 7); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("too long: %v", err)
	}
	// Written once but never counted: sent again from the same offset, it lands in the same place.
	d.Append("ab12", "u_01", 5, strings.NewReader(", fie"), 7)
	if n, err := d.Append("ab12", "u_01", 5, strings.NewReader(", field"), 7); err != nil || n != 7 {
		t.Fatalf("second chunk: %v %d", err, n)
	}
	if err := d.Commit("ab12", "u_01", sum(whole)); err != nil {
		t.Fatal(err)
	}
	f, err := d.Open("ab12", sum(whole))
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(f)
	f.Close()
	if string(b) != whole {
		t.Fatalf("got %q", b)
	}
	if list, _ := d.List("ab12"); len(list) != 1 {
		t.Fatalf("parts are not files: %+v", list)
	}

	// A part that is not the file named is dropped.
	d.Append("ab12", "u_02", 0, strings.NewReader("other"), 5)
	if err := d.Commit("ab12", "u_02", sum(whole)); !errors.Is(err, ErrMismatch) {
		t.Fatalf("mismatch: %v", err)
	}
	if _, err := os.Stat(d.part("ab12", "u_02")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("the part goes: %v", err)
	}
	d.Append("ab12", "u_03", 0, strings.NewReader("x"), 5)
	if err := d.Discard("ab12", "u_03"); err != nil {
		t.Fatal(err)
	}
	if err := d.Discard("ab12", "u_03"); err != nil {
		t.Fatalf("gone already: %v", err)
	}
	if _, err := d.Append("ab12", "../u_04", 0, strings.NewReader("x"), 5); !errors.Is(err, ErrBadHash) {
		t.Fatalf("traversal: %v", err)
	}
}

func TestCommitOverAStoredFile(t *testing.T) {
	d := &Disk{Root: t.TempDir()}
	d.Put("ab12", sum("same"), strings.NewReader("same"))
	open, err := d.Open("ab12", sum("same"))
	if err != nil {
		t.Fatal(err)
	}
	defer open.Close()
	d.Append("ab12", "u_09", 0, strings.NewReader("same"), 4)
	if err := d.Commit("ab12", "u_09", sum("same")); err != nil {
		t.Fatalf("the file is there already, open: %v", err)
	}
	if _, err := os.Stat(d.part("ab12", "u_09")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("the part goes: %v", err)
	}
}

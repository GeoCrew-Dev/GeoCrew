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

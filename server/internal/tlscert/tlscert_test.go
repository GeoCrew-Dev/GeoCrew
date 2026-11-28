package tlscert

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

func TestSelfSignedIsMadeOnceAndKept(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "tls")
	now := time.Date(2026, 11, 28, 0, 0, 0, 0, time.UTC)
	first, err := SelfSigned(dir, now)
	if err != nil {
		t.Fatal(err)
	}
	fp, err := Fingerprint(first)
	if err != nil {
		t.Fatal(err)
	}
	if len(fp) != 43 {
		t.Fatalf("a SHA-256 as base64url without padding is 43 characters: %q", fp)
	}
	again, err := SelfSigned(dir, now.AddDate(1, 0, 0))
	if err != nil {
		t.Fatal(err)
	}
	if fp2, _ := Fingerprint(again); fp2 != fp {
		t.Fatalf("a restart keeps the key: %s, then %s", fp, fp2)
	}
	leaf, _ := x509.ParseCertificate(first.Certificate[0])
	sum := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
	if base64.RawURLEncoding.EncodeToString(sum[:]) != fp {
		t.Fatal("the fingerprint is the SHA-256 of the public key")
	}
	if !leaf.NotAfter.After(now.AddDate(19, 0, 0)) {
		t.Fatalf("long-lived, as phones pin the key: %v", leaf.NotAfter)
	}
	if runtime.GOOS != "windows" {
		info, err := os.Stat(filepath.Join(dir, KeyFile))
		if err != nil || info.Mode().Perm() != 0o600 {
			t.Fatalf("the key is the server's alone: %v %v", info.Mode(), err)
		}
	}
}

func TestAClientPinningTheKeyConnects(t *testing.T) {
	cert, err := SelfSigned(t.TempDir(), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	fp, _ := Fingerprint(cert)
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) }))
	srv.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	srv.StartTLS()
	defer srv.Close()
	// As a phone does: no certificate authority, only the pinned key.
	pinned := func(pin string) *http.Client {
		return &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true,
			VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
				leaf, err := x509.ParseCertificate(raw[0])
				if err != nil {
					return err
				}
				sum := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
				if base64.RawURLEncoding.EncodeToString(sum[:]) != pin {
					return x509.UnknownAuthorityError{}
				}
				return nil
			},
		}}}
	}
	if resp, err := pinned(fp).Get(srv.URL); err != nil {
		t.Fatal(err)
	} else {
		resp.Body.Close()
	}
	if _, err := pinned("wrong").Get(srv.URL); err == nil {
		t.Fatal("another key is refused")
	}
}

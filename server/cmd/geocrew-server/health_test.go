package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHealthcheckTriesTLSThenPlain(t *testing.T) {
	ok := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/healthz" {
			http.NotFound(w, r)
		}
	})
	tlsSrv := httptest.NewTLSServer(ok)
	defer tlsSrv.Close()
	plain := httptest.NewServer(ok)
	defer plain.Close()
	for _, base := range []string{strings.TrimPrefix(tlsSrv.URL, "https://"), strings.TrimPrefix(plain.URL, "http://"), plain.URL} {
		if err := healthcheck(base); err != nil {
			t.Fatalf("%s: %v", base, err)
		}
	}
	down := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(503) }))
	defer down.Close()
	if err := healthcheck(strings.TrimPrefix(down.URL, "https://")); err == nil || !strings.Contains(err.Error(), "503") {
		t.Fatalf("an unhealthy TLS server is reported as such: %v", err)
	}
}

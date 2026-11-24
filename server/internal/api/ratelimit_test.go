package api

import (
	"net/http/httptest"
	"testing"
	"time"
)

func TestLimiterRefills(t *testing.T) {
	l := newLimiter(60, 2) // one per second, burst two
	now := time.Unix(1000, 0)
	l.now = func() time.Time { return now }
	for i := 0; i < 2; i++ {
		if ok, _ := l.allow("a"); !ok {
			t.Fatalf("attempt %d refused within burst", i)
		}
	}
	if ok, wait := l.allow("a"); ok || wait <= 0 {
		t.Fatalf("third attempt allowed (wait %v)", wait)
	}
	if ok, _ := l.allow("b"); !ok {
		t.Fatal("another client was limited")
	}
	now = now.Add(1100 * time.Millisecond)
	if ok, _ := l.allow("a"); !ok {
		t.Fatal("token not refilled")
	}
}

func TestClientIP(t *testing.T) {
	r := httptest.NewRequest("POST", "/", nil)
	r.RemoteAddr = "10.0.0.1:5555"
	r.Header.Set("X-Forwarded-For", "1.2.3.4, 5.6.7.8")
	if got := clientIP(r, false); got != "10.0.0.1" {
		t.Fatalf("untrusted: %s", got)
	}
	if got := clientIP(r, true); got != "5.6.7.8" {
		t.Fatalf("trusted: %s", got)
	}
}

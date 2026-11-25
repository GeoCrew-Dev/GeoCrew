package push

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
)

type fakeTargets struct {
	mu      sync.Mutex
	list    []store.PushTarget
	deleted []string
}

func (f *fakeTargets) PushTargets(context.Context, string) ([]store.PushTarget, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]store.PushTarget{}, f.list...), nil
}

func (f *fakeTargets) DeletePushTarget(_ context.Context, _ string, member string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.deleted = append(f.deleted, member)
	return nil
}

func quiet() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func TestDeliveryFollowsAudienceAndEvents(t *testing.T) {
	var mu sync.Mutex
	got := map[string][]Notice{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var n Notice
		if r.Header.Get("Content-Type") != "application/json" || json.NewDecoder(r.Body).Decode(&n) != nil {
			t.Errorf("bad request")
		}
		mu.Lock()
		got[r.URL.Path] = append(got[r.URL.Path], n)
		mu.Unlock()
		if r.URL.Path == "/gone" {
			w.WriteHeader(http.StatusGone)
		}
	}))
	defer srv.Close()
	targets := &fakeTargets{list: []store.PushTarget{
		{MemberID: "a", Endpoint: srv.URL + "/a", Events: Events},
		{MemberID: "b", Endpoint: srv.URL + "/b", Events: []string{EventTask}},
		{MemberID: "c", Endpoint: srv.URL + "/gone", Events: Events},
	}}
	s := New(targets, Options{AllowPrivate: true, Log: quiet()})
	s.Notify(Notice{Type: EventMessage, Workspace: "w", From: "a", Seq: 3}, Audience{Except: "a"})
	s.Notify(Notice{Type: EventTask, Workspace: "w", Feature: "f1", From: "a"}, Audience{Only: "b", Except: "a"})
	s.Close()

	if len(got["/a"]) != 0 {
		t.Fatalf("sender must not hear its own message: %v", got["/a"])
	}
	if len(got["/b"]) != 1 || got["/b"][0].Type != EventTask || got["/b"][0].Feature != "f1" {
		t.Fatalf("b: %v", got["/b"])
	}
	if len(got["/gone"]) != 1 || len(targets.deleted) != 1 || targets.deleted[0] != "c" {
		t.Fatalf("gone endpoint should be removed: %v %v", got["/gone"], targets.deleted)
	}
}

func TestNoticeCarriesNoContent(t *testing.T) {
	b, _ := json.Marshal(Notice{Type: EventComment, Workspace: "w", Feature: "f", From: "m", Seq: 9})
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	for k := range m {
		if k != "type" && k != "workspace" && k != "feature" && k != "from" && k != "seq" {
			t.Fatalf("unexpected field %s", k)
		}
	}
}

func TestPrivateAddressesAreRefused(t *testing.T) {
	hit := false
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { hit = true }))
	defer srv.Close()
	targets := &fakeTargets{list: []store.PushTarget{{MemberID: "a", Endpoint: srv.URL, Events: Events}}}
	s := New(targets, Options{Log: quiet(), Timeout: 2 * time.Second})
	s.Notify(Notice{Type: EventMessage, Workspace: "w"}, Audience{})
	s.Close()
	if hit {
		t.Fatal("loopback endpoint must not be called without AllowPrivate")
	}
	if len(targets.deleted) != 0 {
		t.Fatal("a refused endpoint is not 'gone'")
	}
}

func TestCheckEndpoint(t *testing.T) {
	strict := New(&fakeTargets{}, Options{Log: quiet()})
	defer strict.Close()
	lan := New(&fakeTargets{}, Options{AllowPrivate: true, Log: quiet()})
	defer lan.Close()
	cases := []struct {
		url           string
		strict, onLAN bool
	}{
		{"https://ntfy.sh/upAbc123?up=1", true, true},
		{"http://ntfy.sh/up", false, true},
		{"https://192.168.1.5/up", false, true},
		{"https://127.0.0.1/up", false, true},
		{"https://localhost/up", false, true},
		{"https://user:pw@ntfy.sh/up", false, false},
		{"ftp://ntfy.sh/up", false, false},
		{"/relative", false, false},
		{"https://" + strings.Repeat("a", 2100) + ".com/", false, false},
	}
	for _, c := range cases {
		if got := strict.CheckEndpoint(c.url) == nil; got != c.strict {
			t.Errorf("strict %q: %v", c.url, got)
		}
		if got := lan.CheckEndpoint(c.url) == nil; got != c.onLAN {
			t.Errorf("lan %q: %v", c.url, got)
		}
	}
}

func TestFullQueueDropsInsteadOfBlocking(t *testing.T) {
	block := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { <-block }))
	defer srv.Close()
	targets := &fakeTargets{list: []store.PushTarget{{MemberID: "a", Endpoint: srv.URL, Events: Events}}}
	s := New(targets, Options{AllowPrivate: true, Workers: 1, Queue: 1, Log: quiet()})
	done := make(chan struct{})
	go func() {
		for range 10 {
			s.Notify(Notice{Type: EventMessage, Workspace: "w"}, Audience{})
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Notify blocked")
	}
	close(block)
	s.Close()
}

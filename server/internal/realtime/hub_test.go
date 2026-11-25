package realtime

import (
	"encoding/json"
	"testing"
	"time"
)

func client(id string) *Client {
	return &Client{MemberID: id, Nickname: "n-" + id, Send: make(chan []byte, 16)}
}

func next(t *testing.T, c *Client) map[string]any {
	t.Helper()
	select {
	case b := <-c.Send:
		var m map[string]any
		if err := json.Unmarshal(b, &m); err != nil {
			t.Fatal(err)
		}
		return m
	default:
		t.Fatalf("no message for %s", c.MemberID)
		return nil
	}
}

func empty(t *testing.T, c *Client) {
	t.Helper()
	select {
	case b := <-c.Send:
		t.Fatalf("unexpected message for %s: %s", c.MemberID, b)
	default:
	}
}

func TestHubFlow(t *testing.T) {
	h := NewHub()
	now := time.Date(2026, 11, 24, 8, 0, 0, 0, time.UTC)
	h.now = func() time.Time { return now }

	a, b := client("a"), client("b")
	h.Join("w", a)
	if m := next(t, a); m["type"] != TypeHello {
		t.Fatalf("hello: %v", m)
	}

	if !h.UpdatePosition("w", a, Position{Lon: 116.4, Lat: 39.9, Accuracy: 5}) {
		t.Fatal("position rejected")
	}
	if h.UpdatePosition("w", a, Position{Lon: 116.4, Lat: 39.9}) {
		t.Fatal("throttle not applied")
	}
	if h.UpdatePosition("w", a, Position{Lon: 200, Lat: 0}) {
		t.Fatal("invalid position accepted")
	}

	h.Join("w", b)
	hi := next(t, b)
	if pos := hi["positions"].([]any); len(pos) != 1 {
		t.Fatalf("hello positions: %v", hi)
	}
	if online := hi["online"].([]any); len(online) != 1 || online[0] != "a" {
		t.Fatalf("hello online: %v", hi)
	}
	if m := next(t, a); m["type"] != TypePresence || m["member_id"] != "b" || m["online"] != true {
		t.Fatalf("presence: %v", m)
	}

	now = now.Add(2 * time.Second)
	h.UpdatePosition("w", b, Position{Lon: 1, Lat: 2})
	if m := next(t, a); m["type"] != TypePosition || m["member_id"] != "b" || m["nickname"] != "n-b" {
		t.Fatalf("relay: %v", m)
	}
	empty(t, b)

	h.FeatureChanged("w", 7)
	for _, c := range []*Client{a, b} {
		if m := next(t, c); m["type"] != TypeFeatureChanged || m["seq"] != float64(7) {
			t.Fatalf("feature_changed: %v", m)
		}
	}

	dropped := make(chan struct{})
	b.Close = func() { close(dropped) }
	h.Kick("w", "b")
	<-dropped
	if m := next(t, a); m["type"] != TypePresence || m["online"] != false {
		t.Fatalf("offline presence: %v", m)
	}
	if got := h.Online("w"); len(got) != 1 || got[0] != "a" {
		t.Fatalf("online: %v", got)
	}

	// Stale positions are not replayed.
	now = now.Add(time.Hour)
	c := client("c")
	h.Join("w", c)
	if pos := next(t, c)["positions"].([]any); len(pos) != 0 {
		t.Fatalf("stale positions replayed: %v", pos)
	}
}

func TestSlowConsumerDropped(t *testing.T) {
	h := NewHub()
	slow := &Client{MemberID: "s", Send: make(chan []byte, 1)}
	dropped := make(chan struct{})
	slow.Close = func() { close(dropped) }
	h.Join("w", slow) // fills the buffer with hello
	h.FeatureChanged("w", 1)
	select {
	case <-dropped:
	case <-time.After(time.Second):
		t.Fatal("slow client not dropped")
	}
}

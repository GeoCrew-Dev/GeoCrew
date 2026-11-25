package merge

import (
	"encoding/json"
	"fmt"
	"math/rand"
	"os"
	"testing"
	"time"
)

// sharedCases mirrors protocol/testdata/merge/cases.json, which the Android
// client reads too.
type sharedCases struct {
	Defaults struct {
		ServerNow time.Time `json:"server_now"`
		Seq       int64     `json:"seq"`
		Live      []string  `json:"live"`
	} `json:"defaults"`
	Cases []struct {
		Name   string   `json:"name"`
		Live   []string `json:"live"`
		Row    *Row     `json:"row"`
		Change Change   `json:"change"`
		Expect struct {
			Row       json.RawMessage `json:"row"`
			Conflicts json.RawMessage `json:"conflicts"`
		} `json:"expect"`
	} `json:"cases"`
}

// canonical re-encodes JSON with sorted keys, so equal values compare equal.
func canonical(t *testing.T, v any) string {
	t.Helper()
	raw, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	var x any
	if err := json.Unmarshal(raw, &x); err != nil {
		t.Fatal(err)
	}
	out, _ := json.Marshal(x)
	return string(out)
}

func TestSharedCases(t *testing.T) {
	data, err := os.ReadFile("../../../protocol/testdata/merge/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var file sharedCases
	if err := json.Unmarshal(data, &file); err != nil {
		t.Fatal(err)
	}
	if len(file.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, c := range file.Cases {
		t.Run(c.Name, func(t *testing.T) {
			live := map[string]bool{}
			names := file.Defaults.Live
			if c.Live != nil {
				names = c.Live
			}
			for _, id := range names {
				live[id] = true
			}
			row, conflicts := Apply(c.Row, c.Change, live, file.Defaults.Seq, file.Defaults.ServerNow)
			if conflicts == nil {
				conflicts = []Conflict{}
			}
			if got, want := canonical(t, row), canonical(t, c.Expect.Row); got != want {
				t.Errorf("row\n got %s\nwant %s", got, want)
			}
			if got, want := canonical(t, conflicts), canonical(t, c.Expect.Conflicts); got != want {
				t.Errorf("conflicts\n got %s\nwant %s", got, want)
			}
		})
	}
}

// TestNothingIsLost interleaves offline edits from clients with skewed clocks
// and checks, after every push, that each value pushed either made it into the
// row or was recorded as a conflict, and that every value overwritten by a
// concurrent change was recorded too (ADR 0008).
func TestNothingIsLost(t *testing.T) {
	rng := rand.New(rand.NewSource(1))
	fields := []string{"f1", "f2", "f3", "geom"}
	live := map[string]bool{}
	for _, f := range fields {
		live[f] = true
	}
	type client struct {
		id      string
		skew    time.Duration
		baseSeq int64
		pending map[string]Edit
		delete  *Edit
	}
	clients := []*client{
		{id: "m1", skew: 0},
		{id: "m2", skew: 37 * time.Minute},
		{id: "m3", skew: -2 * time.Hour},
	}
	for _, c := range clients {
		c.pending = map[string]Edit{}
	}
	var row *Row
	var seq int64
	now := time.Date(2026, 11, 25, 8, 0, 0, 0, time.UTC)
	edits := 0
	kinds := map[string]int{}

	for step := 0; step < 2000; step++ {
		now = now.Add(time.Duration(1+rng.Intn(120)) * time.Second)
		c := clients[rng.Intn(len(clients))]
		switch r := rng.Intn(10); {
		case r < 6: // edit a field offline
			f := fields[rng.Intn(len(fields))]
			edits++
			value, _ := json.Marshal(fmt.Sprintf("%s-%d", c.id, edits))
			c.pending[f] = Edit{Value: value, EditedAt: now.Add(c.skew)}
		case r < 7: // delete or restore offline
			c.delete = &Edit{Value: json.RawMessage(fmt.Sprint(rng.Intn(2) == 0)), EditedAt: now.Add(c.skew)}
		default: // push, then pull the server's row
			if len(c.pending) == 0 && c.delete == nil {
				continue
			}
			ch := Change{By: c.id, BaseSeq: c.baseSeq, ClientNow: now.Add(c.skew), Set: c.pending, Delete: c.delete}
			seq++
			before := row
			next, conflicts := Apply(row, ch, live, seq, now)
			for _, k := range conflicts {
				kinds[k.Kind]++
			}
			recorded := func(kind, field string, value json.RawMessage) bool {
				for _, k := range conflicts {
					if k.Kind == kind && k.Field == field && k.Lost != nil && sameValue(k.Lost.Value, value) {
						return true
					}
				}
				return false
			}
			for f, e := range ch.Set {
				if got, ok := next.Fields[f]; !ok || (!sameValue(got.Value, e.Value) && !recorded(KindValue, f, e.Value)) {
					t.Fatalf("step %d: %s's %s=%s vanished", step, c.id, f, e.Value)
				}
				if before != nil {
					if old, ok := before.Fields[f]; ok && old.Seq > ch.BaseSeq &&
						!sameValue(next.Fields[f].Value, old.Value) && !recorded(KindValue, f, old.Value) {
						t.Fatalf("step %d: concurrent %s=%s was overwritten without a record", step, f, old.Value)
					}
				}
			}
			if d := ch.Delete; d != nil && string(d.Value) == "true" {
				deleted := next.Deleted != nil && string(next.Deleted.Value) == "true"
				if !deleted && !recorded(KindDeleteRejected, "", d.Value) {
					t.Fatalf("step %d: %s's delete vanished", step, c.id)
				}
			}
			row = &next
			c.baseSeq = seq
			c.pending = map[string]Edit{}
			c.delete = nil
		}
	}
	if seq < 300 {
		t.Fatalf("only %d pushes; the test did not exercise much", seq)
	}
	for _, kind := range []string{KindValue, KindDeleteRejected, KindDeleteOverridden} {
		if kinds[kind] == 0 {
			t.Errorf("no %s conflict came up; the interleaving is too tame", kind)
		}
	}
	t.Logf("%d pushes, conflicts: %v", seq, kinds)
}

func TestEditTime(t *testing.T) {
	server := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	client := server.Add(time.Hour) // the phone runs an hour fast
	if got := EditTime(client.Add(-10*time.Minute), client, server); !got.Equal(server.Add(-10 * time.Minute)) {
		t.Fatal(got)
	}
	if got := EditTime(client.Add(time.Minute), client, server); !got.Equal(server) {
		t.Fatal("an edit time after the client's clock must count as now", got)
	}
}

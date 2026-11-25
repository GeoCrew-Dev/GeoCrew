package store

import (
	"encoding/json"
	"testing"
)

func TestKeepLocked(t *testing.T) {
	keys := []string{"review"}
	prev := json.RawMessage(`{"title":"D1","review":{"state":"approved","by":"L"}}`)
	for _, c := range []struct {
		name, next, prev, want string
	}{
		{"unchanged bytes kept", `{"title":"D2","review":{"by":"L","state":"approved"}}`, string(prev), `{"title":"D2","review":{"by":"L","state":"approved"}}`},
		{"forged review undone", `{"title":"D2","review":{"state":"redo"}}`, string(prev), `{"review":{"state":"approved","by":"L"},"title":"D2"}`},
		{"dropped review restored", `{"title":"D2"}`, string(prev), `{"review":{"state":"approved","by":"L"},"title":"D2"}`},
		{"new feature can't bring one", `{"title":"D2","review":{"state":"approved"}}`, ``, `{"title":"D2"}`},
		{"null properties", `null`, string(prev), `{"review":{"state":"approved","by":"L"}}`},
		{"not an object left alone", `[1]`, string(prev), `[1]`},
	} {
		got := KeepLocked(json.RawMessage(c.next), json.RawMessage(c.prev), keys)
		if !sameJSON(string(got), c.want) {
			t.Errorf("%s: got %s want %s", c.name, got, c.want)
		}
	}
	if got := KeepLocked(json.RawMessage(`{"a":1}`), prev, nil); string(got) != `{"a":1}` {
		t.Errorf("no keys: %s", got)
	}
}

func sameJSON(a, b string) bool {
	var x, y any
	if json.Unmarshal([]byte(a), &x) != nil || json.Unmarshal([]byte(b), &y) != nil {
		return a == b
	}
	xb, _ := json.Marshal(x)
	yb, _ := json.Marshal(y)
	return string(xb) == string(yb)
}

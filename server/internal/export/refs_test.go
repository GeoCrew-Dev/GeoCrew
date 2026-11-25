package export

import (
	"reflect"
	"strings"
	"testing"
)

func TestSettingsRefsCountsOverlaysAndReferenceLayers(t *testing.T) {
	a, b := strings.Repeat("a", 64), strings.Repeat("b", 64)
	got := SettingsRefs([]byte(`{"overlays":[{"file":"` + a + `"},{"file":"nope"}],"reference_layers":[{"id":"r","file":"` + b + `"}],"form":[]}`))
	if want := []string{a, b}; !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	if got := SettingsRefs([]byte(`{"reference_layers":"oops"}`)); got != nil {
		t.Fatalf("malformed settings should give no refs, got %v", got)
	}
}

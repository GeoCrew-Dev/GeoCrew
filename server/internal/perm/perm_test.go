package perm

import (
	"encoding/json"
	"os"
	"testing"
)

// TestSharedCases runs protocol/testdata/permissions/cases.json, which the
// Android client reads too.
func TestSharedCases(t *testing.T) {
	data, err := os.ReadFile("../../../protocol/testdata/permissions/cases.json")
	if err != nil {
		t.Fatal(err)
	}
	var file struct {
		Workspace Workspace `json:"workspace"`
		Cases     []struct {
			Name  string `json:"name"`
			Who   string `json:"who"`
			Perm  string `json:"perm"`
			Layer string `json:"layer"`
			Row   *struct {
				Perm, Layer, Owner string
			} `json:"row"`
			ManageRole *struct {
				Role, Via string
			} `json:"manage_role"`
			Grant *struct {
				Role, Perm string
				Level      Level
			} `json:"grant"`
			ManageMember string `json:"manage_member"`
			Expect       bool   `json:"expect"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(data, &file); err != nil {
		t.Fatal(err)
	}
	if len(file.Cases) == 0 {
		t.Fatal("no cases")
	}
	w := file.Workspace
	for _, c := range file.Cases {
		t.Run(c.Name, func(t *testing.T) {
			var got bool
			switch {
			case c.Row != nil:
				got = w.CanTouchRow(c.Who, c.Row.Perm, c.Row.Layer, c.Row.Owner)
			case c.ManageRole != nil:
				got = w.CanManageRole(c.Who, c.ManageRole.Role, c.ManageRole.Via)
			case c.Grant != nil:
				got = w.CanGrant(c.Who, c.Grant.Role, c.Grant.Perm, c.Grant.Level)
			case c.ManageMember != "":
				got = w.CanManageMember(c.Who, c.ManageMember)
			case c.Layer != "":
				got = w.Level(c.Who, c.Perm, c.Layer) > None
			case c.Perm != "":
				got = w.Has(c.Who, c.Perm)
			default:
				t.Fatal("the case asks nothing")
			}
			if got != c.Expect {
				t.Fatalf("got %v, want %v", got, c.Expect)
			}
		})
	}
}

func TestLevelJSON(t *testing.T) {
	for in, want := range map[string]Level{`false`: None, `"none"`: None, `"own"`: Own, `true`: All, `"all"`: All} {
		var l Level
		if err := json.Unmarshal([]byte(in), &l); err != nil || l != want {
			t.Fatalf("%s: got %v, %v", in, l, err)
		}
	}
	var l Level
	if err := json.Unmarshal([]byte(`"some"`), &l); err == nil {
		t.Fatal("an unknown level must be refused")
	}
}

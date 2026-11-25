package export

import (
	"context"
	"encoding/json"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// Kind is where a record refers to an attachment and how a package names it.
type Kind struct {
	Property string // array of hashes in a record's properties
	Dir      string // folder inside a project package
	Ext      string // file extension inside a project package
}

// Kinds lists every property that refers to attachments (protocol v0 §6).
// A new kind of attachment is one line here; pruning and packages follow.
var Kinds = []Kind{
	{Property: "photos", Dir: "photos", Ext: ".jpg"},
	{Property: "audio", Dir: "audio", Ext: ".m4a"},
}

// Ref is one attachment a record refers to.
type Ref struct {
	SHA  string
	Kind Kind
}

// Refs returns the attachments a record's properties refer to, kind by kind
// in the order of [Kinds] and in list order within a kind, without
// duplicates. Anything that is not a valid hash is ignored.
func Refs(props json.RawMessage) []Ref {
	var p map[string]json.RawMessage
	if len(props) == 0 || json.Unmarshal(props, &p) != nil {
		return nil
	}
	var out []Ref
	seen := map[string]bool{}
	for _, k := range Kinds {
		var list []json.RawMessage
		if json.Unmarshal(p[k.Property], &list) != nil {
			continue
		}
		for _, raw := range list {
			var sha string
			if json.Unmarshal(raw, &sha) == nil && blob.ValidHash(sha) && !seen[sha] {
				seen[sha] = true
				out = append(out, Ref{SHA: sha, Kind: k})
			}
		}
	}
	return out
}

// SettingsRefs returns the attachments the shared settings refer to: the
// pictures of shared overlays (protocol v0 §2.2, key `overlays`).
func SettingsRefs(settings json.RawMessage) []string {
	var s struct {
		Overlays []struct {
			File string `json:"file"`
		} `json:"overlays"`
	}
	if len(settings) == 0 || json.Unmarshal(settings, &s) != nil {
		return nil
	}
	var out []string
	for _, o := range s.Overlays {
		if blob.ValidHash(o.File) {
			out = append(out, o.File)
		}
	}
	return out
}

// Referenced collects every attachment any revision of any record in the
// workspace refers to: current, deleted and superseded ones, since history
// can restore any of them; plus the shared overlay pictures.
func Referenced(ctx context.Context, st store.Store, workspaceID string) (map[string]bool, error) {
	refs := map[string]bool{}
	settings, _, err := st.Settings(ctx, workspaceID)
	if err != nil {
		return nil, err
	}
	for _, sha := range SettingsRefs(settings) {
		refs[sha] = true
	}
	var since int64
	for {
		fs, more, err := st.PullFeatures(ctx, workspaceID, since, page)
		if err != nil {
			return nil, err
		}
		for _, f := range fs {
			since = f.Seq
			for _, r := range Refs(f.Properties) {
				refs[r.SHA] = true
			}
			old, err := st.FeatureHistory(ctx, workspaceID, f.ID)
			if err != nil {
				return nil, err
			}
			for _, o := range old {
				for _, r := range Refs(o.Properties) {
					refs[r.SHA] = true
				}
			}
		}
		if !more || len(fs) == 0 {
			return refs, nil
		}
	}
}

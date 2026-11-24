package export

import (
	"context"
	"encoding/json"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/blob"
	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// PhotoRefs returns the attachment hashes a record's properties list under
// "photos" (protocol v0 §6), in order and without duplicates. Anything
// that is not a valid hash is ignored.
func PhotoRefs(props json.RawMessage) []string {
	var p struct {
		Photos []json.RawMessage `json:"photos"`
	}
	if len(props) == 0 || json.Unmarshal(props, &p) != nil {
		return nil
	}
	var out []string
	seen := map[string]bool{}
	for _, raw := range p.Photos {
		var sha string
		if json.Unmarshal(raw, &sha) == nil && blob.ValidHash(sha) && !seen[sha] {
			seen[sha] = true
			out = append(out, sha)
		}
	}
	return out
}

// Referenced collects every attachment any revision of any record in the
// workspace refers to: current, deleted and superseded ones, since history
// can restore any of them.
func Referenced(ctx context.Context, st store.Store, workspaceID string) (map[string]bool, error) {
	refs := map[string]bool{}
	var since int64
	for {
		fs, more, err := st.PullFeatures(ctx, workspaceID, since, page)
		if err != nil {
			return nil, err
		}
		for _, f := range fs {
			since = f.Seq
			for _, sha := range PhotoRefs(f.Properties) {
				refs[sha] = true
			}
			old, err := st.FeatureHistory(ctx, workspaceID, f.ID)
			if err != nil {
				return nil, err
			}
			for _, o := range old {
				for _, sha := range PhotoRefs(o.Properties) {
					refs[sha] = true
				}
			}
		}
		if !more || len(fs) == 0 {
			return refs, nil
		}
	}
}

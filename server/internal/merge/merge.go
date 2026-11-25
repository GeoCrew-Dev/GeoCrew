// Package merge applies one change from a client to a row, field by field, as
// protocol v1 specifies (protocol/docs/protocol-v1.md, "合并规则"; ADR 0008).
//
// It is a pure function: the caller loads the row, calls Apply and stores the
// result and the conflicts in one transaction. The rules are checked against
// the shared cases in protocol/testdata/merge, which the Android client reads
// too.
package merge

import (
	"bytes"
	"encoding/json"
	"sort"
	"time"
)

// Field is a field's current value with where it came from.
type Field struct {
	Value json.RawMessage `json:"value"`
	By    string          `json:"by"`  // member who set it
	At    time.Time       `json:"at"`  // when it was edited, on the server's clock
	Seq   int64           `json:"seq"` // the server sequence number that last changed it
}

// Row is a row as the server stores it. A deleted row keeps its fields.
type Row struct {
	Fields  map[string]Field `json:"fields"`
	Deleted *Field           `json:"deleted,omitempty"` // value true or false; nil if never deleted or restored
}

// Edit is one field's new value from a client, with the client's edit time.
type Edit struct {
	Value    json.RawMessage `json:"value"`
	EditedAt time.Time       `json:"edited_at"`
}

// Change is one row's edits from one client since the version it last saw.
type Change struct {
	By        string          `json:"by"`
	BaseSeq   int64           `json:"base_seq"` // 0 for a row the client created
	ClientNow time.Time       `json:"client_now"`
	Set       map[string]Edit `json:"set,omitempty"`
	Delete    *Edit           `json:"delete,omitempty"` // value true deletes, false restores
}

// Conflict kinds.
const (
	KindValue            = "value"             // both changed the same field; Lost is the losing value
	KindDeletedField     = "deleted_field"     // the field was removed from the layer; Lost is the edit
	KindDeleteOverridden = "delete_overridden" // someone else deleted the row, this change edited it; the edit wins
	KindDeleteRejected   = "delete_rejected"   // this change deleted the row, someone else edited it meanwhile; the edits win
)

// Conflict records a value that did not make it into the row, so nothing is lost.
type Conflict struct {
	Kind  string `json:"kind"`
	Field string `json:"field,omitempty"`
	Kept  *Field `json:"kept,omitempty"`
	Lost  *Field `json:"lost,omitempty"`
}

// EditTime converts a client's edit time to the server's clock:
// server now − (client now − edit time), never later than server now.
func EditTime(editedAt, clientNow, serverNow time.Time) time.Time {
	t := serverNow.Add(-clientNow.Sub(editedAt))
	if t.After(serverNow) {
		return serverNow
	}
	return t
}

// Apply merges change into row (nil for a new row) and returns the new row
// and the conflicts. live holds the layer's current field ids; seq is the
// sequence number the server gives this change; serverNow is when it arrived.
func Apply(row *Row, ch Change, live map[string]bool, seq int64, serverNow time.Time) (Row, []Conflict) {
	out := Row{Fields: map[string]Field{}}
	if row != nil {
		for id, f := range row.Fields {
			out.Fields[id] = f
		}
		out.Deleted = row.Deleted
	}
	var conflicts []Conflict
	concurrent := func(f Field) bool { return f.Seq > ch.BaseSeq }

	ids := make([]string, 0, len(ch.Set))
	for id := range ch.Set {
		ids = append(ids, id)
	}
	sort.Strings(ids) // conflicts come out in a stable order
	for _, id := range ids {
		e := ch.Set[id]
		in := Field{Value: e.Value, By: ch.By, At: EditTime(e.EditedAt, ch.ClientNow, serverNow), Seq: seq}
		if !live[id] {
			lost := in
			conflicts = append(conflicts, Conflict{Kind: KindDeletedField, Field: id, Lost: &lost})
			continue
		}
		cur, ok := out.Fields[id]
		switch {
		case !ok || !concurrent(cur):
			out.Fields[id] = in
		case sameValue(cur.Value, in.Value):
			// Both made the same change: nothing to resolve.
		case in.At.Before(cur.At):
			lost := in
			kept := cur
			conflicts = append(conflicts, Conflict{Kind: KindValue, Field: id, Kept: &kept, Lost: &lost})
		default: // the later edit wins; on equal times, the one that arrived later
			lost := cur
			kept := in
			out.Fields[id] = in
			conflicts = append(conflicts, Conflict{Kind: KindValue, Field: id, Kept: &kept, Lost: &lost})
		}
	}

	editedOthers := false // fields someone else changed since this client's base
	if row != nil {
		for _, f := range row.Fields {
			if concurrent(f) {
				editedOthers = true
				break
			}
		}
	}
	deleted := out.Deleted != nil && string(out.Deleted.Value) == "true"
	switch {
	case ch.Delete != nil && string(ch.Delete.Value) == "true":
		in := Field{Value: json.RawMessage("true"), By: ch.By, At: EditTime(ch.Delete.EditedAt, ch.ClientNow, serverNow), Seq: seq}
		if editedOthers && !deleted {
			lost := in
			conflicts = append(conflicts, Conflict{Kind: KindDeleteRejected, Lost: &lost})
		} else if !deleted {
			out.Deleted = &in
		}
	case ch.Delete != nil:
		if deleted {
			in := Field{Value: json.RawMessage("false"), By: ch.By, At: EditTime(ch.Delete.EditedAt, ch.ClientNow, serverNow), Seq: seq}
			out.Deleted = &in
		}
	case deleted && concurrent(*out.Deleted) && len(ch.Set) > 0:
		lost := *out.Deleted
		restored := Field{Value: json.RawMessage("false"), By: ch.By, At: serverNow, Seq: seq}
		out.Deleted = &restored
		conflicts = append(conflicts, Conflict{Kind: KindDeleteOverridden, Lost: &lost})
	}
	return out, conflicts
}

// sameValue compares JSON values, ignoring formatting and object key order.
func sameValue(a, b json.RawMessage) bool {
	var x, y any
	if json.Unmarshal(a, &x) != nil || json.Unmarshal(b, &y) != nil {
		return bytes.Equal(a, b)
	}
	ca, _ := json.Marshal(x)
	cb, _ := json.Marshal(y)
	return bytes.Equal(ca, cb)
}

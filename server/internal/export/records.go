package export

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// Record is the part of a live feature that a list or a search needs:
// what it is, what it says and where, without the full properties.
type Record struct {
	ID        string
	Kind      string
	Title     string
	Lithology string
	Note      string
	Samples   []string
	// Lon and Lat are the first position of the geometry; nil without one.
	Lon, Lat *float64
	// UpdatedAt and UpdatedBy (nickname, or the member id when unknown)
	// are of the latest change.
	UpdatedAt time.Time
	UpdatedBy string
	Feature   store.Feature
}

// EachRecord calls fn for every live record of a workspace, in sync order.
func EachRecord(ctx context.Context, st store.Store, workspaceID string, fn func(Record) error) error {
	if _, err := st.Workspace(ctx, workspaceID); err != nil {
		return err
	}
	names := memberNames(ctx, st, workspaceID)
	return eachLive(ctx, st, workspaceID, func(f store.Feature) error {
		return fn(toRecord(f, names))
	})
}

// errFound stops the walk in [LiveRecord].
var errFound = errors.New("found")

// LiveRecord returns the current revision of one live record, or
// store.ErrNotFound when there is none or it was deleted.
func LiveRecord(ctx context.Context, st store.Store, workspaceID, id string) (Record, error) {
	var out Record
	err := EachRecord(ctx, st, workspaceID, func(r Record) error {
		if r.ID == id {
			out = r
			return errFound
		}
		return nil
	})
	switch {
	case errors.Is(err, errFound):
		return out, nil
	case err != nil:
		return out, err
	}
	return out, store.ErrNotFound
}

func toRecord(f store.Feature, names map[string]string) Record {
	r := Record{ID: f.ID, UpdatedAt: f.UpdatedAt, UpdatedBy: f.UpdatedBy, Feature: f}
	if n := names[f.UpdatedBy]; n != "" {
		r.UpdatedBy = n
	}
	var p struct {
		Kind      any `json:"kind"`
		Title     any `json:"title"`
		Lithology any `json:"lithology"`
		Note      any `json:"note"`
		Samples   []struct {
			No any `json:"no"`
		} `json:"samples"`
	}
	if json.Unmarshal(f.Properties, &p) == nil {
		r.Kind = str(p.Kind)
		r.Title = strings.TrimSpace(str(p.Title))
		r.Lithology = strings.TrimSpace(str(p.Lithology))
		r.Note = strings.TrimSpace(str(p.Note))
		for _, s := range p.Samples {
			if no := strings.TrimSpace(str(s.No)); no != "" {
				r.Samples = append(r.Samples, no)
			}
		}
	}
	if lon, lat, _ := firstPosition(f.Geometry); lon != "" {
		x, e1 := strconv.ParseFloat(lon, 64)
		y, e2 := strconv.ParseFloat(lat, 64)
		if e1 == nil && e2 == nil {
			r.Lon, r.Lat = &x, &y
		}
	}
	return r
}

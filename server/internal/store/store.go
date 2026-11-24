// Package store defines the persistence contract of the GeoCrew server.
//
// The HTTP layer depends only on the Store interface, so the default SQLite
// backend (package sqlite) can later be swapped for PostgreSQL/PostGIS
// without touching handlers.
package store

import (
	"context"
	"encoding/json"
	"errors"
	"time"
)

// Role is a member's permission level inside a workspace.
type Role string

const (
	RoleLeader   Role = "leader"
	RoleMember   Role = "member"
	RoleReadonly Role = "readonly"
)

// Valid reports whether r is a known role.
func (r Role) Valid() bool {
	switch r {
	case RoleLeader, RoleMember, RoleReadonly:
		return true
	}
	return false
}

// CanWrite reports whether the role may push features and upload files.
func (r Role) CanWrite() bool { return r == RoleLeader || r == RoleMember }

// Workspace is one field team's shared work area.
type Workspace struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	CreatedAt  time.Time `json:"created_at"`
	InviteCode string    `json:"-"`
	LastSeq    int64     `json:"last_seq"`
}

// Member is one person in a workspace.
type Member struct {
	ID          string    `json:"id"`
	WorkspaceID string    `json:"-"`
	Nickname    string    `json:"nickname"`
	Role        Role      `json:"role"`
	JoinedAt    time.Time `json:"joined_at"`
	Revoked     bool      `json:"revoked,omitempty"`
}

// Feature is one GeoJSON feature plus its sync metadata.
// Geometry and Properties are kept as raw JSON so the server never loses
// fields it does not understand (forward compatibility with newer clients).
type Feature struct {
	ID         string
	Geometry   json.RawMessage
	Properties json.RawMessage
	Version    int64
	UpdatedAt  time.Time
	UpdatedBy  string
	Deleted    bool
	Seq        int64
}

// Newer reports whether f should replace cur under the v0 conflict rule:
// higher version wins, then later updated_at, then larger updated_by.
func (f Feature) Newer(cur Feature) bool {
	if f.Version != cur.Version {
		return f.Version > cur.Version
	}
	if !f.UpdatedAt.Equal(cur.UpdatedAt) {
		return f.UpdatedAt.After(cur.UpdatedAt)
	}
	return f.UpdatedBy > cur.UpdatedBy
}

// SameRevision reports whether f and cur describe the same revision, which
// happens when a client retries a push that already succeeded.
func (f Feature) SameRevision(cur Feature) bool {
	return f.Version == cur.Version && f.UpdatedAt.Equal(cur.UpdatedAt) && f.UpdatedBy == cur.UpdatedBy
}

// PushStatus is the outcome of one feature in a push.
type PushStatus string

const (
	// PushApplied: the change was stored and got a new seq.
	PushApplied PushStatus = "applied"
	// PushDuplicate: the same revision was already stored (idempotent retry).
	PushDuplicate PushStatus = "duplicate"
	// PushStale: the server holds a newer revision; Current carries it.
	PushStale PushStatus = "stale"
)

// PushResult reports what happened to one pushed feature.
type PushResult struct {
	ID      string
	Status  PushStatus
	Seq     int64
	Current *Feature
}

// FileMeta describes an attachment stored by content hash.
type FileMeta struct {
	SHA256      string    `json:"sha256"`
	Size        int64     `json:"size"`
	ContentType string    `json:"content_type"`
	CreatedAt   time.Time `json:"created_at"`
	CreatedBy   string    `json:"created_by"`
}

var (
	ErrNotFound     = errors.New("not found")
	ErrUnauthorized = errors.New("unauthorized")
	ErrBadInvite    = errors.New("invalid invite code")
	ErrInvalid      = errors.New("invalid request")
	ErrLastLeader   = errors.New("workspace must keep at least one leader")
)

// Backuper is implemented by stores that can write a consistent copy of
// themselves while the server keeps running.
type Backuper interface {
	Backup(ctx context.Context, dest string) error
}

// Store is the persistence contract. Implementations must be safe for
// concurrent use.
type Store interface {
	// CreateWorkspace creates a workspace and its first leader. The returned
	// token is shown once; only its hash is stored.
	CreateWorkspace(ctx context.Context, name, nickname string) (Workspace, Member, string, error)
	// Join adds a member using an invite code.
	Join(ctx context.Context, inviteCode, nickname string) (Workspace, Member, string, error)
	// Authenticate resolves a bearer token to a non-revoked member of the workspace.
	Authenticate(ctx context.Context, workspaceID, token string) (Member, error)

	Workspace(ctx context.Context, id string) (Workspace, error)
	// Workspaces lists every workspace on the server, oldest first (for administration).
	Workspaces(ctx context.Context) ([]Workspace, error)
	Members(ctx context.Context, workspaceID string) ([]Member, error)
	ResetInvite(ctx context.Context, workspaceID string) (string, error)
	SetRole(ctx context.Context, workspaceID, memberID string, role Role) error
	RevokeMember(ctx context.Context, workspaceID, memberID string) error

	// PushFeatures applies changes atomically and returns one result per input
	// plus the workspace's latest seq. UpdatedBy is forced to memberID.
	PushFeatures(ctx context.Context, workspaceID, memberID string, fs []Feature) ([]PushResult, int64, error)
	// PullFeatures returns features with seq > since, ordered by seq.
	PullFeatures(ctx context.Context, workspaceID string, since int64, limit int) ([]Feature, bool, error)
	// FeatureHistory returns superseded revisions of a feature, newest first.
	FeatureHistory(ctx context.Context, workspaceID, featureID string) ([]Feature, error)

	PutFileMeta(ctx context.Context, workspaceID string, meta FileMeta) error
	FileMeta(ctx context.Context, workspaceID, sha256 string) (FileMeta, error)

	Close() error
}

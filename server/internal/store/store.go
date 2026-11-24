// Package store defines the persistence contract of the GeoCrew server.
//
// The HTTP layer depends only on the Store interface. Package sqldb
// implements it once for SQL databases; packages sqlite (the default) and
// postgres open it on their engine, and storetest checks any backend.
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
	// SettingsVersion increases every time the shared settings change.
	SettingsVersion int64 `json:"settings_version"`
}

// ViewTokenPrefix starts every view-link token, so the two kinds of token
// never collide and logs or support can tell them apart.
const ViewTokenPrefix = "v_"

// MaxSettingsBytes bounds a workspace's shared settings document.
const MaxSettingsBytes = 64 << 10

// Member is one person in a workspace.
type Member struct {
	ID          string    `json:"id"`
	WorkspaceID string    `json:"-"`
	Nickname    string    `json:"nickname"`
	Role        Role      `json:"role"`
	JoinedAt    time.Time `json:"joined_at"`
	Revoked     bool      `json:"revoked,omitempty"`
	// LastSeen is when the member last made an authenticated request,
	// to the minute; nil before the first one (protocol v0 §3).
	LastSeen *time.Time `json:"last_seen,omitempty"`
	// Viewer is set when the request came with a view link the member made
	// (protocol v0 §10): the role is then read-only and the request is not
	// the member's own activity.
	Viewer bool `json:"-"`
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
	// Previous is the revision an applied change replaced (nil for a new feature).
	Previous *Feature
}

// PushTarget is where a member's device wants notifications: a UnifiedPush
// or similar endpoint URL that accepts a POSTed message.
type PushTarget struct {
	MemberID string   `json:"-"`
	Endpoint string   `json:"endpoint"`
	Events   []string `json:"events"`
	// Keys, when set, make the server encrypt notices (Web Push, RFC 8291).
	Keys      *PushKeys `json:"keys,omitempty"`
	UpdatedAt time.Time `json:"updated_at"`
}

// PushKeys are a device's Web Push keys as base64url strings.
type PushKeys struct {
	P256dh string `json:"p256dh"`
	Auth   string `json:"auth"`
}

// MaxPushEndpoint bounds a push endpoint URL.
const MaxPushEndpoint = 2048

// Comment is one message in a workspace's discussion. FeatureID ties it to a
// record; an empty FeatureID is a message in the workspace's general stream.
// At optionally pins a general message to a map position as [lng, lat].
type Comment struct {
	ID        string    `json:"id"`
	FeatureID string    `json:"feature_id,omitempty"`
	Body      string    `json:"body"`
	At        []float64 `json:"at,omitempty"`
	AuthorID  string    `json:"author_id"`
	Nickname  string    `json:"nickname"`
	CreatedAt time.Time `json:"created_at"`
	Seq       int64     `json:"seq"`
	Deleted   bool      `json:"deleted,omitempty"`
	// Urgent marks a workspace message as a call for help or to gather;
	// clients alert loudly and every member is notified (protocol §8.1).
	Urgent bool `json:"urgent,omitempty"`
}

// MaxCommentRunes bounds a comment body.
const MaxCommentRunes = 2000

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
	// ErrStale means a conditional update was based on an outdated version.
	ErrStale = errors.New("stale version")
	// ErrForbidden means the caller may not act on this object.
	ErrForbidden = errors.New("forbidden")
	// ErrConflict means an id is already used for different content.
	ErrConflict = errors.New("conflict")
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
	// CreateViewLink issues a read-only token for a browser, made by
	// memberID and valid until expires. It starts with [ViewTokenPrefix],
	// is shown once and only its hash is stored. Authenticate accepts it as
	// the member with Role readonly and Viewer set, until it expires, the
	// member revokes their links or the member is removed.
	CreateViewLink(ctx context.Context, workspaceID, memberID string, expires time.Time) (string, error)
	// RevokeViewLinks ends the member's view links and returns how many were still valid.
	RevokeViewLinks(ctx context.Context, workspaceID, memberID string) (int, error)
	// Touch records that the member was active at the given time.
	Touch(ctx context.Context, memberID string, at time.Time) error

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

	// Settings returns the workspace's shared settings (a JSON object the
	// server does not interpret) and their version; a new workspace has "{}" at 0.
	Settings(ctx context.Context, workspaceID string) (json.RawMessage, int64, error)
	// PutSettings replaces the settings and returns the new version. A
	// baseVersion >= 0 must equal the current version, otherwise ErrStale.
	PutSettings(ctx context.Context, workspaceID string, settings json.RawMessage, baseVersion int64) (int64, error)

	// AddComment stores a comment from authorID with a new comment seq. Adding
	// the same id again with the same author, feature and body returns the
	// stored comment (idempotent retry); any other reuse is ErrConflict.
	AddComment(ctx context.Context, workspaceID, authorID string, c Comment) (Comment, error)
	// Comments returns comments with seq > since in seq order, including
	// deleted ones (with an empty body) so clients can drop their copies.
	Comments(ctx context.Context, workspaceID string, since int64, limit int) ([]Comment, bool, error)
	// DeleteComment blanks a comment and gives it a new seq. Only its author
	// or a leader (byLeader) may delete; others get ErrForbidden.
	DeleteComment(ctx context.Context, workspaceID, commentID, memberID string, byLeader bool) (Comment, error)

	// SetPushTarget stores (replacing) the member's push endpoint.
	SetPushTarget(ctx context.Context, workspaceID string, t PushTarget) error
	// PushTarget returns the member's endpoint, or ErrNotFound.
	PushTarget(ctx context.Context, workspaceID, memberID string) (PushTarget, error)
	// DeletePushTarget removes the member's endpoint; removing none is not an error.
	DeletePushTarget(ctx context.Context, workspaceID, memberID string) error
	// PushTargets lists the endpoints of the workspace's non-revoked members.
	PushTargets(ctx context.Context, workspaceID string) ([]PushTarget, error)

	PutFileMeta(ctx context.Context, workspaceID string, meta FileMeta) error
	FileMeta(ctx context.Context, workspaceID, sha256 string) (FileMeta, error)

	Close() error
}

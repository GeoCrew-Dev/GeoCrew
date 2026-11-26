// Package v1 is the storage contract for protocol v1
// (protocol/docs/protocol-v1.md): workspaces whose members sign in per device,
// Discord-style roles, layers and rows merged field by field.
//
// It lives next to the v0 store (package store) until the API moves to v1 in
// M3; then the v0 store goes away. Package sqldb implements it on the same
// database, in tables prefixed v1_, so the two never touch each other's data;
// package v1test checks any backend. Errors are package store's
// (store.ErrNotFound, store.ErrInvalid, ...).
package v1

import (
	"context"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
)

// Workspace is one team's shared work area.
type Workspace struct {
	ID         string
	Name       string
	InviteCode string
	Leader     string // the leader's member id
	CreatedAt  time.Time
	ArchivedAt *time.Time
}

// Member is one person in a workspace. Roles are the roles besides the base
// role perm.Base, sorted. AccessVersion goes up whenever what the member may
// do changes (protocol 10.2).
type Member struct {
	ID            string
	WorkspaceID   string
	Nickname      string
	Roles         []string
	JoinedAt      time.Time
	AccessVersion int64
}

// Device is one phone or computer of a member; each has its own token.
type Device struct {
	ID        string
	MemberID  string
	Name      string
	CreatedAt time.Time
}

// Joined is what creating or joining a workspace returns. Token is shown
// once; only its hash is stored.
type Joined struct {
	Workspace Workspace
	Member    Member
	Device    Device
	Token     string
}

// DefaultBasePermissions are what the base role may do in a new workspace,
// about what a member could do in v0 (protocol 4.1). The leader can change them.
var DefaultBasePermissions = map[string]perm.Level{
	perm.EditRows:        perm.All,
	perm.DeleteRows:      perm.All,
	perm.AssignTasks:     perm.All,
	perm.SendMessages:    perm.All,
	perm.ViewLocations:   perm.All,
	perm.ExportData:      perm.All,
	perm.CreateViewLinks: perm.All,
	perm.CreateAILinks:   perm.All,
}

// BaseRoleName is the base role's name; clients may show their own wording for it.
const BaseRoleName = "组员"

// Store is the protocol v1 storage contract. Implementations must be safe for
// concurrent use.
type Store interface {
	// CreateWorkspace creates a workspace with its base role and makes the
	// creator its leader, signed in on their first device.
	CreateWorkspace(ctx context.Context, name, nickname, device string) (Joined, error)
	// Join adds a new member, with only the base role, on their first device.
	// An unknown code is store.ErrBadInvite; an archived workspace refuses
	// with store.ErrArchived.
	Join(ctx context.Context, inviteCode, nickname, device string) (Joined, error)
	// Authenticate resolves a device token to its member and device. A
	// revoked device, a removed member, a token of another workspace or no
	// token at all is store.ErrUnauthorized.
	Authenticate(ctx context.Context, workspaceID, token string) (Member, Device, error)

	Workspace(ctx context.Context, id string) (Workspace, error)
	// Members lists the workspace's current members, earliest first.
	Members(ctx context.Context, workspaceID string) ([]Member, error)
	// Access returns what package perm needs to decide what members may do:
	// the leader, the roles and each current member's roles.
	Access(ctx context.Context, workspaceID string) (perm.Workspace, error)
}

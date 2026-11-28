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
	"encoding/json"
	"errors"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/merge"
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
	ID         string
	MemberID   string
	Name       string
	CreatedAt  time.Time
	LastSeenAt *time.Time
}

// Joined is what creating or joining a workspace returns. Token is shown
// once; only its hash is stored.
type Joined struct {
	Workspace Workspace
	Member    Member
	Device    Device
	Token     string
}

// WorkspaceUpdate renames, archives or reopens a workspace; nil fields stay as they are.
type WorkspaceUpdate struct {
	Name     *string
	Archived *bool
}

// Role is a named set of permissions (protocol 4.1, 5.4). Position orders
// roles, higher is more senior; the base role perm.Base sits at 0. Version
// goes up at every change, for UpdateRole's baseVersion.
type Role struct {
	ID          string
	Name        string
	Position    int
	Permissions map[string]perm.Level
	Version     int64
}

// RoleUpdate changes a role; nil fields stay. Permissions, when set,
// replaces the whole set.
type RoleUpdate struct {
	Name        *string
	Position    *int
	Permissions map[string]perm.Level
}

// Geometry types a layer can have (protocol 7.1); None is a table without geometry.
var GeometryTypes = map[string]bool{
	"Point": true, "LineString": true, "Polygon": true,
	"MultiPoint": true, "MultiLineString": true, "MultiPolygon": true, "None": true,
}

// Field types (protocol 7.2).
var FieldTypes = map[string]bool{
	"string": true, "integer": true, "real": true, "boolean": true, "date": true, "datetime": true, "json": true,
}

// FieldDef defines one field of a layer (protocol 7.2). Options, when set,
// is a JSON array of the values a form offers.
type FieldDef struct {
	ID      string          `json:"id"`
	Name    string          `json:"name"`
	Type    string          `json:"type"`
	Options json.RawMessage `json:"options,omitempty"`
}

// Layer properties merged field by field like a row's (protocol 7.1).
const (
	PropName  = "name"
	PropStyle = "style"
	PropForm  = "form"
	PropOrder = "order"
)

// Layer is a shared layer: its schema, changed online only, and its
// properties, merged like a row's fields. Seq is the workspace sequence
// number of its last change.
type Layer struct {
	ID            string
	GeometryType  string
	CRS           string
	Fields        []FieldDef
	SchemaVersion int64
	Props         map[string]merge.Field
	Deleted       *merge.Field // value true or false; nil if never deleted
	CreatedBy     string
	Seq           int64
}

// LayerInput creates a layer (protocol 5.6). ID is the client's UUID.
type LayerInput struct {
	ID           string
	Name         string
	GeometryType string
	CRS          string
	Fields       []FieldDef
	Style        json.RawMessage
	Form         json.RawMessage
	Order        *float64
}

// SchemaOp is one step of a schema change (protocol 7.3): add_field (Field),
// rename_field (ID, Name), set_options (ID, Options) or delete_field (ID).
type SchemaOp struct {
	Op      string
	Field   *FieldDef
	ID      string
	Name    string
	Options json.RawMessage
}

// System tables whose rows are not in a layer of their own (protocol 7.1, 9.1).
const (
	TableLayers = "layers" // a change here edits a layer's merged properties; Row is the layer id
	// TableAttachments holds attachment rows: target_layer and target_row say
	// which row a file belongs to, and that row's permissions apply.
	TableAttachments = "attachments"
)

// Change is one row's edits from a client (protocol 6.2, 8.1). ID is the
// client's UUID for the change, kept so a retry is recognised.
type Change struct {
	ID      string
	Layer   string
	Row     string
	BaseSeq int64
	Set     map[string]merge.Edit
	Delete  *merge.Edit
}

// Push outcomes (protocol 8.1).
const (
	Applied   = "applied"
	Duplicate = "duplicate"
	Rejected  = "rejected"
)

// Machine codes of a rejected change (protocol 8.1).
const (
	RejectUnknownLayer = "unknown_layer"
	RejectInvalidValue = "invalid_value"
	RejectForbidden    = "forbidden"
	RejectArchived     = "archived"
)

// Result is what became of one pushed change.
type Result struct {
	ID        string
	Status    string
	Seq       int64      // applied: the change's seq; duplicate: the seq it got then
	Conflicts []Conflict // applied: the conflicts it made, possibly none
	Error     string     // rejected: one of the Reject codes
	Message   string     // rejected: for people
}

// Conflict is a conflict record (protocol 6.5): a value that did not make it
// into the row, kept so nothing is lost.
type Conflict struct {
	ID       string
	Layer    string // the row's layer, or TableLayers
	Row      string
	Seq      int64 // the change that made it
	Resolved bool
	merge.Conflict
}

// Row is a row as stored: every field with who set it, when and at which
// seq; deleted rows keep their fields. Owner is the member who first
// submitted it. Conflicted is set while it has unresolved conflicts.
type Row struct {
	Layer      string
	ID         string
	Owner      string
	Fields     map[string]merge.Field
	Deleted    *merge.Field
	Conflicted bool
	Seq        int64
}

// MaxPull bounds a pull (protocol 3.3, max_pull_batch).
const MaxPull = 1000

// Pulled is one page of changes since a seq (protocol 8.2): the current state
// of every layer, row and conflict record that changed, as the member may
// see them. Seq is the next since; More means pull again at once.
type Pulled struct {
	Layers    []Layer
	Rows      []Row
	Conflicts []Conflict
	Seq       int64
	More      bool
}

// Ways to resolve a conflict (protocol 8.3).
const (
	ChooseKept = "kept" // keep the current value; only mark the conflict resolved
	ChooseLost = "lost" // put the losing value back, as a change by the resolver
)

// File uploads (protocol 9.2).
const (
	MaxFileSize  = 1 << 30            // bytes; max_file_size
	ChunkSize    = 1 << 20            // bytes the server takes per chunk
	UploadExpiry = 7 * 24 * time.Hour // an upload untouched this long is dropped
)

var (
	// ErrTooLarge means a file is over MaxFileSize.
	ErrTooLarge = errors.New("file too large")
	// ErrOffsetMismatch means a chunk does not start where the upload stands;
	// the current offset comes with it.
	ErrOffsetMismatch = errors.New("offset mismatch")
)

// Upload is a file on its way in, by chunks (protocol 9.2). The bytes are
// the caller's to keep; the store tracks how far they got.
type Upload struct {
	ID          string
	WorkspaceID string
	MemberID    string
	SHA256      string
	Size        int64
	Type        string
	Offset      int64 // bytes received so far
	UpdatedAt   time.Time
}

// AuditEntry is one line of the audit log (protocol 5.7). Target is the
// member, device or role acted on.
type AuditEntry struct {
	ID     int64
	At     time.Time
	By     string
	Action string
	Target string
	Detail json.RawMessage
}

// Audit actions.
const (
	AuditRemoveMember   = "remove_member"
	AuditLeave          = "leave"
	AuditTransferLeader = "transfer_leader"
	AuditRevokeDevice   = "revoke_device"
	AuditTransferCode   = "transfer_code"      // a transfer code was issued for Target
	AuditTransferUsed   = "transfer_code_used" // Target is the device it signed in
	AuditCreateRole     = "create_role"
	AuditUpdateRole     = "update_role"
	AuditDeleteRole     = "delete_role"
	AuditSetRoles       = "set_roles"    // Target is the member
	AuditSetOverride    = "set_override" // Target is the layer
)

// ErrLeaderMustTransfer means the leader tried to leave without handing over first.
var ErrLeaderMustTransfer = errors.New("the leader must hand over before leaving")

// TransferCodeTTL is how long a transfer code works (protocol 5.3).
const TransferCodeTTL = 10 * time.Minute

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

	// The calls below act for actor, a current member, and check what actor
	// may do with package perm: store.ErrForbidden when not allowed,
	// store.ErrNotFound for an unknown workspace, member or device.

	// UpdateWorkspace renames, archives or reopens the workspace; actor needs
	// manage_workspace. Archiving an archived workspace keeps its time.
	UpdateWorkspace(ctx context.Context, workspaceID, actor string, u WorkspaceUpdate) (Workspace, error)
	// ResetInvite replaces the invite code; the old one stops working. Actor
	// needs manage_members.
	ResetInvite(ctx context.Context, workspaceID, actor string) (string, error)
	// SetNickname changes a member's own nickname.
	SetNickname(ctx context.Context, workspaceID, member, nickname string) (Member, error)
	// RemoveMember takes target out of the workspace and ends all their
	// devices. Members may remove themselves (leave), but the leader must hand
	// over first (ErrLeaderMustTransfer); removing someone else needs
	// perm's CanManageMember.
	RemoveMember(ctx context.Context, workspaceID, actor, target string) error
	// TransferLeader makes another current member the leader; only the leader
	// may. The old leader keeps their other roles.
	TransferLeader(ctx context.Context, workspaceID, actor, to string) error

	// Devices lists member's working devices, earliest first; the member and
	// those who can manage them may read it.
	Devices(ctx context.Context, workspaceID, actor, member string) ([]Device, error)
	// RevokeDevice ends a device's token; its owner, or someone who can
	// manage the owner, may.
	RevokeDevice(ctx context.Context, workspaceID, actor, deviceID string) error
	// Touch records that a device was active.
	Touch(ctx context.Context, deviceID string, at time.Time) error
	// CreateTransferCode issues a one-time code that signs a new device in as
	// member, for TransferCodeTTL. Only the member and the leader may; a new
	// code replaces the member's unused ones.
	CreateTransferCode(ctx context.Context, workspaceID, actor, member string) (string, time.Time, error)
	// JoinWithTransferCode signs a new device in as the code's member, with
	// their roles and rows. An unknown, used or expired code is store.ErrBadInvite.
	JoinWithTransferCode(ctx context.Context, code, device string) (Joined, error)

	// Roles lists the workspace's roles, most senior first.
	Roles(ctx context.Context, workspaceID string) ([]Role, error)
	// CreateRole adds a role. Actor needs manage_roles; the position must be
	// above 0 and below actor's rank, and actor must hold every permission at
	// the level given. Unknown permissions and levels are store.ErrInvalid.
	CreateRole(ctx context.Context, workspaceID, actor, name string, position int, perms map[string]perm.Level) (Role, error)
	// UpdateRole changes a role actor can manage (perm's CanManageRole). The
	// base role only takes Permissions, and only from the leader. Permissions actor does not hold at
	// the old and new level cannot be changed. When baseVersion is not the
	// role's version it returns the current role and store.ErrStale.
	UpdateRole(ctx context.Context, workspaceID, actor, id string, u RoleUpdate, baseVersion int64) (Role, error)
	// DeleteRole removes a role actor can manage and takes it off everyone;
	// the base role cannot go (store.ErrInvalid).
	DeleteRole(ctx context.Context, workspaceID, actor, id string) error
	// SetMemberRoles replaces target's roles besides the base role. Actor must
	// be able to manage target, and to give (manage_members) every role
	// added or taken away.
	SetMemberRoles(ctx context.Context, workspaceID, actor, target string, roles []string) (Member, error)

	// Layers lists the workspace's layers, deleted ones included, oldest change first.
	Layers(ctx context.Context, workspaceID string) ([]Layer, error)
	// Layer returns one layer, deleted or not.
	Layer(ctx context.Context, workspaceID, id string) (Layer, error)
	// CreateLayer creates a shared layer with schema version 1; actor needs
	// manage_layers. Creating an id again with the same schema returns the
	// layer as it is (a retry); with another schema it returns the current
	// layer and store.ErrStale.
	CreateLayer(ctx context.Context, workspaceID, actor string, in LayerInput) (Layer, error)
	// DeleteLayer marks a layer deleted and RestoreLayer brings it back; both
	// need manage_layers and leave its rows in place.
	DeleteLayer(ctx context.Context, workspaceID, actor, id string) (Layer, error)
	RestoreLayer(ctx context.Context, workspaceID, actor, id string) (Layer, error)
	// ChangeSchema applies ops in order, all or none, and raises the schema
	// version; actor needs edit_schema and must see the layer. A baseVersion
	// that is not the current one returns the current layer and store.ErrStale.
	ChangeSchema(ctx context.Context, workspaceID, actor, id string, baseVersion int64, ops []SchemaOp) (Layer, error)
	// LayerOverrides returns the layer's overrides: role -> permission -> level.
	LayerOverrides(ctx context.Context, workspaceID, layer string) (map[string]map[string]perm.Level, error)
	// SetLayerOverride replaces role's overrides on a layer (an empty set
	// removes them), as perm's CanSetOverride allows.
	SetLayerOverride(ctx context.Context, workspaceID, actor, layer, role string, set map[string]perm.Level) error

	// Push applies changes from actor in one transaction, each merged field
	// by field (protocol 6.4), and returns one result per change and the
	// workspace's latest seq. clientNow is the client's clock when it sent
	// them. Two changes to one row in a push are store.ErrInvalid.
	Push(ctx context.Context, workspaceID, actor string, clientNow time.Time, changes []Change) ([]Result, int64, error)
	// Pull returns what changed after since, oldest first, at most limit
	// layers and rows (1 to MaxPull). Rows and conflicts of layers actor
	// cannot see, or of deleted layers, are left out, and so are fields
	// deleted from a layer. A non-empty onlyLayers keeps only those layers'
	// rows and conflicts (protocol 10.4 backfill).
	Pull(ctx context.Context, workspaceID, actor string, since int64, limit int, onlyLayers []string) (Pulled, error)
	// ResolveConflict resolves a conflict as protocol 8.3 says. Only the
	// leader and the two sides may (store.ErrForbidden). Choosing lost makes
	// a normal change, which can conflict again; when that change is
	// refused, the conflict stays and the result says why. Resolving a
	// resolved conflict changes nothing.
	ResolveConflict(ctx context.Context, workspaceID, actor, id, choose string) (Conflict, *Result, error)
	// Row returns a row with its unresolved conflicts.
	Row(ctx context.Context, workspaceID, id string) (Row, []Conflict, error)

	// StartUpload begins, or picks up, actor's upload of a file. exists is
	// true when the workspace already has the file and nothing is to be sent.
	// Actor needs edit_rows on some layer; an archived workspace refuses
	// with store.ErrArchived, a file over MaxFileSize with ErrTooLarge.
	StartUpload(ctx context.Context, workspaceID, actor, sha256 string, size int64, mimeType string) (u Upload, exists bool, err error)
	// Received records n bytes that actor added at offset and returns the new
	// offset. An offset other than the current one returns that one and
	// ErrOffsetMismatch; going past the declared size is store.ErrInvalid; an
	// upload that is finished, dropped or not actor's is store.ErrNotFound.
	Received(ctx context.Context, workspaceID, actor, uploadID string, offset, n int64) (int64, error)
	// Upload is actor's unfinished upload uploadID as it stands: ErrNotFound
	// for one finished, voided or someone else's, ErrArchived while archived.
	Upload(ctx context.Context, workspaceID, actor, uploadID string) (Upload, error)
	// FinishUpload ends a complete upload: with hashOK the file is stored,
	// otherwise the upload is dropped.
	FinishUpload(ctx context.Context, workspaceID, actor, uploadID string, hashOK bool) error
	// ExpiredUploads lists uploads untouched since before, for the cleaner;
	// DropUpload removes one.
	ExpiredUploads(ctx context.Context, before time.Time) ([]Upload, error)
	DropUpload(ctx context.Context, workspaceID, uploadID string) error
	// FileAccess tells whether actor may download a file: some attachment row
	// referring to it (as file or thumb) is one actor can see. uploaded is
	// whether the bytes are here yet (protocol 9.2, not_uploaded).
	FileAccess(ctx context.Context, workspaceID, actor, sha256 string) (visible, uploaded bool, err error)

	// Audit lists the audit log newest first, starting below id before (0 for
	// the newest), at most limit entries (1 to 100). Only the leader may read it.
	Audit(ctx context.Context, workspaceID, actor string, before int64, limit int) ([]AuditEntry, error)
}

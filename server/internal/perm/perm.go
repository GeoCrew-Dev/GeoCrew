// Package perm decides what a member may do in a workspace, as protocol v1
// specifies (protocol/docs/protocol-v1.md, "工作区、成员与权限"; ADR 0009).
//
// The rules are checked against the shared cases in protocol/testdata/permissions,
// which the Android client reads too: offline, the phone must reach the same
// answers the server will.
package perm

import "fmt"

// Level is how far a scoped permission reaches. Plain permissions are None or All.
type Level int

const (
	None Level = iota
	Own        // only rows the member created
	All
)

// UnmarshalJSON reads false, true, "none", "own" or "all".
func (l *Level) UnmarshalJSON(b []byte) error {
	switch string(b) {
	case "false", `"none"`:
		*l = None
	case `"own"`:
		*l = Own
	case "true", `"all"`:
		*l = All
	default:
		return fmt.Errorf("permission level %s", b)
	}
	return nil
}

// Base is the role every member has.
const Base = "member"

// Permission ids. EditRows and DeleteRows take Own; the rest are on or off.
const (
	ManageMembers     = "manage_members"
	ManageRoles       = "manage_roles"
	ManageWorkspace   = "manage_workspace"
	ManageSettings    = "manage_settings"
	PostAnnouncements = "post_announcements"
	ManageLayers      = "manage_layers"
	EditSchema        = "edit_schema"
	EditRows          = "edit_rows"
	DeleteRows        = "delete_rows"
	Review            = "review"
	AssignTasks       = "assign_tasks"
	SendMessages      = "send_messages"
	StartRollcall     = "start_rollcall"
	DeleteMessages    = "delete_messages"
	ViewLocations     = "view_locations"
	ExportData        = "export_data"
	CreateViewLinks   = "create_view_links"
	CreateAILinks     = "create_ai_links"
	ManagePlugins     = "manage_plugins"
	// ViewLayer can only be set per layer; without an override everyone sees every layer.
	ViewLayer = "view_layer"
)

// Overridable lists the permissions a layer override may set.
var Overridable = map[string]bool{ViewLayer: true, EditRows: true, DeleteRows: true}

// Safety rights belong to everyone and no setting can take them away.
var Safety = map[string]bool{
	"send_sos":        true,
	"answer_rollcall": true,
	"report_outing":   true,
	"leave":           true,
	"share_location":  true, // turning one's own location sharing on or off
}

// Role is a named set of permissions; Position orders roles (higher is more senior).
type Role struct {
	Position    int              `json:"position"`
	Permissions map[string]Level `json:"permissions"`
}

// Workspace is what the rules need to know about one workspace.
type Workspace struct {
	Leader  string                                 `json:"leader"`
	Roles   map[string]Role                        `json:"roles"`   // must include Base
	Members map[string][]string                    `json:"members"` // roles besides Base
	Layers  map[string]map[string]map[string]Level `json:"layers"`  // layer -> role -> permission -> level
}

func (w Workspace) rolesOf(member string) []string {
	return append([]string{Base}, w.Members[member]...)
}

// Level gives member's level of perm, on layer if it is not "".
// Layer overrides work as in Discord: the base role's override replaces the
// combined role permissions; if other roles of the member override the same
// permission, the highest of those overrides replaces it again.
func (w Workspace) Level(member, perm, layer string) Level {
	if member == w.Leader {
		return All
	}
	if _, ok := w.Members[member]; !ok {
		return None
	}
	v := None
	if perm == ViewLayer {
		v = All
	}
	for _, r := range w.rolesOf(member) {
		if l := w.Roles[r].Permissions[perm]; l > v {
			v = l
		}
	}
	if layer == "" {
		return v
	}
	overrides := w.Layers[layer]
	if l, ok := overrides[Base][perm]; ok {
		v = l
	}
	found, best := false, None
	for _, r := range w.Members[member] {
		if l, ok := overrides[r][perm]; ok {
			found = true
			if l > best {
				best = l
			}
		}
	}
	if found {
		v = best
	}
	return v
}

// Has reports whether member holds perm at all (anywhere, without layer overrides).
func (w Workspace) Has(member, perm string) bool {
	if Safety[perm] {
		_, ok := w.Members[member]
		return ok || member == w.Leader
	}
	return w.Level(member, perm, "") > None
}

// CanTouchRow reports whether member may edit (EditRows) or delete (DeleteRows)
// a row on layer created by owner. Seeing the layer is required too.
func (w Workspace) CanTouchRow(member, perm, layer, owner string) bool {
	if w.Level(member, ViewLayer, layer) == None {
		return false
	}
	switch w.Level(member, perm, layer) {
	case All:
		return true
	case Own:
		return owner == member
	}
	return false
}

// rank is the position of a member's most senior role; the leader outranks everyone.
func (w Workspace) rank(member string) int {
	if member == w.Leader {
		return int(^uint(0) >> 1)
	}
	best := w.Roles[Base].Position
	for _, r := range w.Members[member] {
		if p := w.Roles[r].Position; p > best {
			best = p
		}
	}
	return best
}

// CanManageRole reports whether actor may edit or delete role, or give it to
// someone: actor needs ManageRoles (or ManageMembers to give it), and the role
// must sit below actor's most senior role.
func (w Workspace) CanManageRole(actor, role, perm string) bool {
	if actor == w.Leader {
		return true
	}
	r, ok := w.Roles[role]
	return ok && w.Has(actor, perm) && r.Position < w.rank(actor)
}

// CanGrant reports whether actor may set perm to level on role: actor must be
// able to manage the role and must itself hold perm at that level or higher.
func (w Workspace) CanGrant(actor, role, perm string, level Level) bool {
	if actor == w.Leader {
		return true
	}
	return w.CanManageRole(actor, role, ManageRoles) && w.Level(actor, perm, "") >= level
}

// CanManageMember reports whether actor may change target's roles or remove
// target: actor needs ManageMembers and must outrank target. Nobody manages
// the leader or themselves this way.
func (w Workspace) CanManageMember(actor, target string) bool {
	if _, ok := w.Members[target]; !ok || target == w.Leader || actor == target {
		return false
	}
	if actor == w.Leader {
		return true
	}
	return w.Has(actor, ManageMembers) && w.rank(target) < w.rank(actor)
}

// CanSetOverride reports whether actor may set role's overrides on a layer to
// set (permission -> level). Like CanGrant, actor must be able to manage the
// role and hold each level itself. Overrides on Base bind everyone, including
// people ranked above actor, so only the leader may set them.
func (w Workspace) CanSetOverride(actor, role string, set map[string]Level) bool {
	if actor == w.Leader {
		return true
	}
	if role == Base || !w.CanManageRole(actor, role, ManageRoles) {
		return false
	}
	for perm, level := range set {
		if !Overridable[perm] || w.Level(actor, perm, "") < level {
			return false
		}
	}
	return true
}

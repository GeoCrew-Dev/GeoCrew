package sqldb

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

// validPermissions accepts known role permissions at levels they can take:
// own only for scoped ones (protocol 4.2).
func validPermissions(ps map[string]perm.Level) error {
	for p, l := range ps {
		if !perm.Permissions[p] || l < perm.None || l > perm.All || (l == perm.Own && !perm.Scoped(p)) {
			return store.ErrInvalid
		}
	}
	return nil
}

func copyPermissions(ps map[string]perm.Level) map[string]perm.Level {
	out := make(map[string]perm.Level, len(ps))
	for p, l := range ps {
		out[p] = l
	}
	return out
}

func (v *V1) Roles(ctx context.Context, workspaceID string) ([]v1.Role, error) {
	if _, err := v.Workspace(ctx, workspaceID); err != nil {
		return nil, err
	}
	return v.roles(ctx, v.d.db, workspaceID, "")
}

// roles lists the workspace's roles, most senior first, or only id when it is not "".
func (v *V1) roles(ctx context.Context, q querier, workspaceID, id string) ([]v1.Role, error) {
	query, args := `SELECT id, name, position, permissions, version FROM v1_roles WHERE workspace_id = ?`, []any{workspaceID}
	if id != "" {
		query, args = query+` AND id = ?`, append(args, id)
	}
	rows, err := q.QueryContext(ctx, query+` ORDER BY position DESC, id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []v1.Role{}
	for rows.Next() {
		var r v1.Role
		var perms string
		if err := rows.Scan(&r.ID, &r.Name, &r.Position, &perms, &r.Version); err != nil {
			return nil, err
		}
		if err := json.Unmarshal([]byte(perms), &r.Permissions); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func (v *V1) role(ctx context.Context, q querier, workspaceID, id string) (v1.Role, error) {
	rs, err := v.roles(ctx, q, workspaceID, id)
	if err == nil && len(rs) == 0 {
		err = store.ErrNotFound
	}
	if err != nil {
		return v1.Role{}, err
	}
	return rs[0], nil
}

func (v *V1) CreateRole(ctx context.Context, workspaceID, actor, name string, position int, perms map[string]perm.Level) (v1.Role, error) {
	name, err := clean(name, 32)
	if err != nil {
		return v1.Role{}, err
	}
	if position <= 0 || validPermissions(perms) != nil {
		return v1.Role{}, store.ErrInvalid
	}
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return v1.Role{}, err
	}
	defer tx.Rollback()
	if !acc.Has(actor, perm.ManageRoles) || position >= acc.Rank(actor) {
		return v1.Role{}, store.ErrForbidden
	}
	for p, l := range perms {
		if acc.Level(actor, p, "") < l { // only what actor holds can be given
			return v1.Role{}, store.ErrForbidden
		}
	}
	r := v1.Role{ID: randHex(8), Name: name, Position: position, Permissions: copyPermissions(perms), Version: 1}
	b, err := perm.MarshalPermissions(r.Permissions)
	if err != nil {
		return v1.Role{}, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO v1_roles (workspace_id, id, name, position, permissions, version) VALUES (?,?,?,?,?,?)`,
		ws.ID, r.ID, r.Name, r.Position, string(b), r.Version); err != nil {
		return v1.Role{}, err
	}
	if err := v.audit(ctx, tx, ws.ID, actor, v1.AuditCreateRole, r.ID, map[string]any{"name": r.Name}, v.clock()); err != nil {
		return v1.Role{}, err
	}
	return r, tx.Commit()
}

func (v *V1) UpdateRole(ctx context.Context, workspaceID, actor, id string, u v1.RoleUpdate, baseVersion int64) (v1.Role, error) {
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return v1.Role{}, err
	}
	defer tx.Rollback()
	cur, err := v.role(ctx, tx, ws.ID, id)
	if err != nil {
		return v1.Role{}, err
	}
	if !acc.CanManageRole(actor, id, perm.ManageRoles) {
		return v1.Role{}, store.ErrForbidden
	}
	if baseVersion != cur.Version {
		return cur, store.ErrStale
	}
	next := cur
	if id == perm.Base {
		if u.Name != nil || u.Position != nil {
			return v1.Role{}, store.ErrInvalid // the base role keeps its name and place
		}
		// Everyone holds the base role, people ranked above actor included,
		// so only the leader may change it (as with its layer overrides).
		if actor != ws.Leader {
			return v1.Role{}, store.ErrForbidden
		}
	}
	if u.Name != nil {
		if next.Name, err = clean(*u.Name, 32); err != nil {
			return v1.Role{}, err
		}
	}
	if u.Position != nil {
		if *u.Position <= 0 {
			return v1.Role{}, store.ErrInvalid
		}
		if *u.Position >= acc.Rank(actor) {
			return v1.Role{}, store.ErrForbidden
		}
		next.Position = *u.Position
	}
	if u.Permissions != nil {
		if validPermissions(u.Permissions) != nil {
			return v1.Role{}, store.ErrInvalid
		}
		for p := range union(cur.Permissions, u.Permissions) {
			old, nw := cur.Permissions[p], u.Permissions[p]
			// A permission actor does not hold at both levels is not theirs to touch.
			if old != nw && acc.Level(actor, p, "") < max(old, nw) {
				return v1.Role{}, store.ErrForbidden
			}
		}
		next.Permissions = copyPermissions(u.Permissions)
	}
	next.Version++
	b, err := perm.MarshalPermissions(next.Permissions)
	if err != nil {
		return v1.Role{}, err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE v1_roles SET name = ?, position = ?, permissions = ?, version = ? WHERE workspace_id = ? AND id = ?`,
		next.Name, next.Position, string(b), next.Version, ws.ID, id); err != nil {
		return v1.Role{}, err
	}
	after := copyAccess(acc)
	after.Roles[id] = perm.Role{Position: next.Position, Permissions: next.Permissions}
	if err := v.bumpChanged(ctx, tx, ws.ID, acc, after); err != nil {
		return v1.Role{}, err
	}
	if err := v.audit(ctx, tx, ws.ID, actor, v1.AuditUpdateRole, id, map[string]any{"version": next.Version}, v.clock()); err != nil {
		return v1.Role{}, err
	}
	return next, tx.Commit()
}

func union(a, b map[string]perm.Level) map[string]bool {
	out := map[string]bool{}
	for p := range a {
		out[p] = true
	}
	for p := range b {
		out[p] = true
	}
	return out
}

func (v *V1) DeleteRole(ctx context.Context, workspaceID, actor, id string) error {
	if id == perm.Base {
		return store.ErrInvalid
	}
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, ok := acc.Roles[id]; !ok {
		return store.ErrNotFound
	}
	if !acc.CanManageRole(actor, id, perm.ManageRoles) {
		return store.ErrForbidden
	}
	for _, q := range []string{
		`DELETE FROM v1_member_roles WHERE workspace_id = ? AND role_id = ?`,
		`DELETE FROM v1_layer_overrides WHERE workspace_id = ? AND role_id = ?`,
		`DELETE FROM v1_roles WHERE workspace_id = ? AND id = ?`,
	} {
		if _, err := tx.ExecContext(ctx, q, ws.ID, id); err != nil {
			return err
		}
	}
	after := copyAccess(acc)
	delete(after.Roles, id)
	for m, rs := range after.Members {
		after.Members[m] = without(rs, id)
	}
	for _, roles := range after.Layers {
		delete(roles, id)
	}
	if err := v.bumpChanged(ctx, tx, ws.ID, acc, after); err != nil {
		return err
	}
	if err := v.audit(ctx, tx, ws.ID, actor, v1.AuditDeleteRole, id, nil, v.clock()); err != nil {
		return err
	}
	return tx.Commit()
}

func without(rs []string, id string) []string {
	out := []string{}
	for _, r := range rs {
		if r != id {
			out = append(out, r)
		}
	}
	return out
}

func (v *V1) SetMemberRoles(ctx context.Context, workspaceID, actor, target string, roles []string) (v1.Member, error) {
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return v1.Member{}, err
	}
	defer tx.Rollback()
	cur, ok := acc.Members[target]
	if !ok {
		return v1.Member{}, store.ErrNotFound
	}
	if !acc.CanManageMember(actor, target) {
		return v1.Member{}, store.ErrForbidden
	}
	want := []string{}
	seen := map[string]bool{}
	for _, r := range roles {
		if _, ok := acc.Roles[r]; !ok || r == perm.Base {
			return v1.Member{}, store.ErrInvalid
		}
		if !seen[r] {
			seen[r] = true
			want = append(want, r)
		}
	}
	sort.Strings(want)
	had := map[string]bool{}
	for _, r := range cur {
		had[r] = true
	}
	// Every role given or taken away must be one actor may give.
	for _, changed := range []map[string]bool{had, seen} {
		for r := range changed {
			if had[r] != seen[r] && !acc.CanManageRole(actor, r, perm.ManageMembers) {
				return v1.Member{}, store.ErrForbidden
			}
		}
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM v1_member_roles WHERE workspace_id = ? AND member_id = ?`, ws.ID, target); err != nil {
		return v1.Member{}, err
	}
	for _, r := range want {
		if _, err := tx.ExecContext(ctx, `INSERT INTO v1_member_roles (workspace_id, member_id, role_id) VALUES (?,?,?)`, ws.ID, target, r); err != nil {
			return v1.Member{}, err
		}
	}
	after := copyAccess(acc)
	after.Members[target] = want
	if err := v.bumpChanged(ctx, tx, ws.ID, acc, after); err != nil {
		return v1.Member{}, err
	}
	if err := v.audit(ctx, tx, ws.ID, actor, v1.AuditSetRoles, target, map[string]any{"roles": want}, v.clock()); err != nil {
		return v1.Member{}, err
	}
	if err := tx.Commit(); err != nil {
		return v1.Member{}, err
	}
	return v.member(ctx, v.d.db, ws.ID, target)
}

// copyAccess copies the maps a change rewrites (roles, members' role lists,
// layer overrides), so the old picture stays intact for comparison.
func copyAccess(w perm.Workspace) perm.Workspace {
	out := perm.Workspace{Leader: w.Leader, Roles: map[string]perm.Role{}, Members: map[string][]string{},
		Layers: map[string]map[string]map[string]perm.Level{}}
	for id, r := range w.Roles {
		out.Roles[id] = r
	}
	for m, rs := range w.Members {
		out.Members[m] = append([]string{}, rs...)
	}
	for layer, roles := range w.Layers {
		out.Layers[layer] = map[string]map[string]perm.Level{}
		for r, set := range roles {
			out.Layers[layer][r] = set
		}
	}
	return out
}

// accessSignature sums up what member may do: rank, every permission and
// the overridable ones on each of layers, so two pictures can be compared.
func accessSignature(w perm.Workspace, member string, layers []string) string {
	var b strings.Builder
	fmt.Fprintf(&b, "%d;", w.Rank(member))
	perms := make([]string, 0, len(perm.Permissions))
	for p := range perm.Permissions {
		perms = append(perms, p)
	}
	sort.Strings(perms)
	for _, p := range perms {
		fmt.Fprintf(&b, "%d", w.Level(member, p, ""))
	}
	for _, l := range layers {
		fmt.Fprintf(&b, ";%s:%d%d%d", l, w.Level(member, perm.ViewLayer, l), w.Level(member, perm.EditRows, l), w.Level(member, perm.DeleteRows, l))
	}
	return b.String()
}

// bumpChanged raises access_version for every member whose permissions
// differ between before and after (protocol 5.7, 10.2).
func (v *V1) bumpChanged(ctx context.Context, tx *Tx, workspaceID string, before, after perm.Workspace) error {
	// Both pictures are read on the same layers, those with overrides in either.
	seen := map[string]bool{}
	layers := []string{}
	for _, w := range []perm.Workspace{before, after} {
		for l := range w.Layers {
			if !seen[l] {
				seen[l] = true
				layers = append(layers, l)
			}
		}
	}
	sort.Strings(layers)
	for m := range after.Members {
		if accessSignature(before, m, layers) == accessSignature(after, m, layers) {
			continue
		}
		if _, err := tx.ExecContext(ctx, `UPDATE v1_members SET access_version = access_version + 1 WHERE workspace_id = ? AND id = ?`,
			workspaceID, m); err != nil {
			return err
		}
	}
	return nil
}

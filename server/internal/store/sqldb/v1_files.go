package sqldb

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"github.com/GeoCrew-Dev/GeoCrew/server/internal/merge"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/perm"
	"github.com/GeoCrew-Dev/GeoCrew/server/internal/store"
	v1 "github.com/GeoCrew-Dev/GeoCrew/server/internal/store/v1"
)

// editsSomewhere reports whether member may edit rows on at least one layer,
// as uploading needs (protocol 9.2).
func editsSomewhere(w perm.Workspace, member string, layers map[string]v1.Layer) bool {
	if w.Level(member, perm.EditRows, "") > perm.None {
		return true
	}
	for id := range layers {
		if w.Level(member, perm.EditRows, id) > perm.None && w.Level(member, perm.ViewLayer, id) > perm.None {
			return true
		}
	}
	return false
}

const uploadCols = `workspace_id, id, member_id, sha256, size, type, received, updated_at`

func scanUpload(r rowScanner) (v1.Upload, error) {
	var u v1.Upload
	var updated string
	err := r.Scan(&u.WorkspaceID, &u.ID, &u.MemberID, &u.SHA256, &u.Size, &u.Type, &u.Offset, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return u, store.ErrNotFound
	}
	u.UpdatedAt = parseTime(updated)
	return u, err
}

func (v *V1) StartUpload(ctx context.Context, workspaceID, actor, sha256 string, size int64, mimeType string) (v1.Upload, bool, error) {
	if !shaPattern.MatchString(sha256) || size < 0 || checkAttachmentField("type", mustJSON(mimeType)) != "" {
		return v1.Upload{}, false, store.ErrInvalid
	}
	if size > v1.MaxFileSize {
		return v1.Upload{}, false, v1.ErrTooLarge
	}
	tx, ws, acc, err := v.begin(ctx, workspaceID)
	if err != nil {
		return v1.Upload{}, false, err
	}
	defer tx.Rollback()
	if ws.ArchivedAt != nil {
		return v1.Upload{}, false, store.ErrArchived
	}
	layers, err := v.layerMap(ctx, tx, ws.ID)
	if err != nil {
		return v1.Upload{}, false, err
	}
	if !editsSomewhere(acc, actor, layers) {
		return v1.Upload{}, false, store.ErrForbidden
	}
	var n int
	if err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM v1_files WHERE workspace_id = ? AND sha256 = ?`, ws.ID, sha256).Scan(&n); err != nil {
		return v1.Upload{}, false, err
	}
	if n > 0 {
		return v1.Upload{}, true, nil
	}
	// The member's unfinished upload of the same file goes on where it stopped.
	u, err := scanUpload(tx.QueryRowContext(ctx, `SELECT `+uploadCols+` FROM v1_uploads WHERE workspace_id = ? AND member_id = ? AND sha256 = ?`,
		ws.ID, actor, sha256))
	if err == nil && u.Size == size {
		return u, false, tx.Commit()
	}
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return v1.Upload{}, false, err
	}
	if err == nil { // the same hash with another size cannot both be right: start over
		if _, err := tx.ExecContext(ctx, `DELETE FROM v1_uploads WHERE workspace_id = ? AND id = ?`, ws.ID, u.ID); err != nil {
			return v1.Upload{}, false, err
		}
	}
	at := v.clock()
	u = v1.Upload{ID: "u_" + randHex(12), WorkspaceID: ws.ID, MemberID: actor, SHA256: sha256, Size: size, Type: mimeType, UpdatedAt: at}
	if _, err := tx.ExecContext(ctx, `INSERT INTO v1_uploads (`+uploadCols+`, created_at) VALUES (?,?,?,?,?,?,0,?,?)`,
		u.WorkspaceID, u.ID, u.MemberID, u.SHA256, u.Size, u.Type, fmtV1Time(at), fmtV1Time(at)); err != nil {
		return v1.Upload{}, false, err
	}
	return u, false, tx.Commit()
}

func mustJSON(s string) json.RawMessage {
	b, _ := json.Marshal(s)
	return b
}

func (v *V1) upload(ctx context.Context, tx *Tx, workspaceID, actor, id string) (v1.Upload, error) {
	ws, err := scanV1Workspace(tx.QueryRowContext(ctx, v1WorkspaceSelect+` WHERE id = ?`, workspaceID))
	if err != nil {
		return v1.Upload{}, err
	}
	if ws.ArchivedAt != nil {
		return v1.Upload{}, store.ErrArchived // uploads stop while archived (protocol 5.1)
	}
	u, err := scanUpload(tx.QueryRowContext(ctx, `SELECT `+uploadCols+` FROM v1_uploads WHERE workspace_id = ? AND id = ?`+v.d.dialect.ForUpdate, workspaceID, id))
	if err == nil && u.MemberID != actor {
		err = store.ErrNotFound // another member's upload is not theirs to fill
	}
	return u, err
}

func (v *V1) Upload(ctx context.Context, workspaceID, actor, uploadID string) (v1.Upload, error) {
	tx, err := v.d.db.BeginTx(ctx, nil)
	if err != nil {
		return v1.Upload{}, err
	}
	defer tx.Rollback()
	return v.upload(ctx, tx, workspaceID, actor, uploadID)
}

func (v *V1) Received(ctx context.Context, workspaceID, actor, uploadID string, offset, n int64) (int64, error) {
	tx, err := v.d.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	u, err := v.upload(ctx, tx, workspaceID, actor, uploadID)
	if err != nil {
		return 0, err
	}
	if offset != u.Offset {
		return u.Offset, v1.ErrOffsetMismatch
	}
	if n < 0 || n > v1.ChunkSize || offset+n > u.Size {
		return u.Offset, store.ErrInvalid
	}
	u.Offset += n
	if _, err := tx.ExecContext(ctx, `UPDATE v1_uploads SET received = ?, updated_at = ? WHERE workspace_id = ? AND id = ?`,
		u.Offset, fmtV1Time(v.clock()), workspaceID, uploadID); err != nil {
		return 0, err
	}
	return u.Offset, tx.Commit()
}

func (v *V1) FinishUpload(ctx context.Context, workspaceID, actor, uploadID string, hashOK bool) error {
	tx, err := v.d.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	u, err := v.upload(ctx, tx, workspaceID, actor, uploadID)
	if err != nil {
		return err
	}
	if u.Offset != u.Size {
		return store.ErrInvalid // not all there yet
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM v1_uploads WHERE workspace_id = ? AND id = ?`, workspaceID, uploadID); err != nil {
		return err
	}
	if hashOK {
		// Another member may have finished the same file meanwhile: one copy is enough.
		if _, err := tx.ExecContext(ctx, `INSERT INTO v1_files (workspace_id, sha256, size, type, created_at, created_by) VALUES (?,?,?,?,?,?)
			ON CONFLICT (workspace_id, sha256) DO NOTHING`, workspaceID, u.SHA256, u.Size, u.Type, fmtV1Time(v.clock()), actor); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (v *V1) ExpiredUploads(ctx context.Context, before time.Time) ([]v1.Upload, error) {
	rows, err := v.d.db.QueryContext(ctx, `SELECT `+uploadCols+` FROM v1_uploads WHERE updated_at < ? ORDER BY updated_at`, fmtV1Time(before))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []v1.Upload{}
	for rows.Next() {
		u, err := scanUpload(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

func (v *V1) DropUpload(ctx context.Context, workspaceID, uploadID string) error {
	_, err := v.d.db.ExecContext(ctx, `DELETE FROM v1_uploads WHERE workspace_id = ? AND id = ?`, workspaceID, uploadID)
	return err
}

func (v *V1) FileAccess(ctx context.Context, workspaceID, actor, sha256 string) (bool, bool, error) {
	if !shaPattern.MatchString(sha256) {
		return false, false, nil
	}
	acc, err := v.Access(ctx, workspaceID)
	if err != nil {
		return false, false, err
	}
	if _, ok := acc.Members[actor]; !ok {
		return false, false, nil
	}
	layers, err := v.layerMap(ctx, v.d.db, workspaceID)
	if err != nil {
		return false, false, err
	}
	// The hash in the row's text narrows the search; the fields decide.
	rows, err := v.d.db.QueryContext(ctx, `SELECT fields FROM v1_rows WHERE workspace_id = ? AND layer_id = ? AND fields LIKE ?`,
		workspaceID, v1.TableAttachments, "%"+sha256+"%")
	if err != nil {
		return false, false, err
	}
	var all []string
	for rows.Next() {
		var f string
		if err := rows.Scan(&f); err != nil {
			rows.Close()
			return false, false, err
		}
		all = append(all, f)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return false, false, err
	}
	visible := false
	for _, f := range all {
		var fields map[string]merge.Field
		if json.Unmarshal([]byte(f), &fields) != nil {
			continue
		}
		if jsonString(fields["file"].Value) != sha256 && jsonString(fields["thumb"].Value) != sha256 {
			continue
		}
		target := jsonString(fields["target_layer"].Value)
		if l, ok := layers[target]; ok && !isDeleted(l) && acc.Level(actor, perm.ViewLayer, target) > perm.None {
			visible = true
			break
		}
	}
	if !visible {
		return false, false, nil
	}
	var n int
	err = v.d.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM v1_files WHERE workspace_id = ? AND sha256 = ?`, workspaceID, sha256).Scan(&n)
	return true, n > 0, err
}

// Package sqldb implements store.Store on any SQL database through
// database/sql. The schema and the queries are shared; a [Dialect] supplies
// what differs between engines: placeholders, row locks, migrations,
// schema versioning and backups. Packages sqlite and postgres open a DB
// with their dialect.
//
// Queries are written with ? placeholders and portable SQL (ON CONFLICT
// upserts, RETURNING, INTEGER flags rather than booleans); keep it that way
// so a change works on every engine.
package sqldb

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"strings"

	"github.com/GeoCrew-Dev/GeoCrew-Server/internal/store"
)

// Dialect is what one database engine needs beyond the shared SQL.
type Dialect struct {
	// Name identifies the engine in errors and logs, e.g. "sqlite".
	Name string
	// Numbered rewrites ? placeholders to $1, $2… (PostgreSQL).
	Numbered bool
	// ForUpdate is appended to a SELECT that must lock the row it reads
	// until the transaction ends (" FOR UPDATE"), or empty when the engine
	// already runs one writer at a time.
	ForUpdate string
	// Migrations bring an empty database to the current schema in order.
	// Never edit an applied one: append a new migration, to every dialect.
	Migrations []string
	// Version reads how many migrations have been applied.
	Version func(ctx context.Context, db *sql.DB) (int, error)
	// Migrate applies migration number n (1-based) inside tx and records it.
	// It may first re-check the version under a lock and return applied=false
	// when another process got there first.
	Migrate func(ctx context.Context, tx *sql.Tx, n int, migration string) (applied bool, err error)
	// Backup writes a consistent copy to dest; nil when the engine has its
	// own tools for that (pg_dump).
	Backup func(ctx context.Context, db *sql.DB, dest string) error
}

// DB implements store.Store on a database/sql handle.
type DB struct {
	db      *conn
	raw     *sql.DB
	dialect Dialect
}

var _ store.Store = (*DB)(nil)

// New wraps an open handle and migrates it to the current schema.
func New(ctx context.Context, db *sql.DB, d Dialect) (*DB, error) {
	out := &DB{db: &conn{db: db, numbered: d.Numbered}, raw: db, dialect: d}
	if err := out.migrate(ctx); err != nil {
		return nil, fmt.Errorf("%s migrate: %w", d.Name, err)
	}
	return out, nil
}

func (d *DB) Close() error { return d.raw.Close() }

// Dialect names the engine behind this store.
func (d *DB) Dialect() string { return d.dialect.Name }

func (d *DB) migrate(ctx context.Context) error {
	v, err := d.dialect.Version(ctx, d.raw)
	if err != nil {
		return err
	}
	for i := v; i < len(d.dialect.Migrations); i++ {
		tx, err := d.raw.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := d.dialect.Migrate(ctx, tx, i+1, d.dialect.Migrations[i]); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %d: %w", i+1, err)
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

// Backup writes a consistent copy of the database to dest, which must not
// exist yet; engines without a built-in way return an error naming their tool.
func (d *DB) Backup(ctx context.Context, dest string) error {
	if d.dialect.Backup == nil {
		return fmt.Errorf("%s 数据库请用它自己的备份工具（如 pg_dump）", d.dialect.Name)
	}
	return d.dialect.Backup(ctx, d.raw, dest)
}

var _ store.Backuper = (*DB)(nil)

// lockWorkspace holds the workspace row until tx ends, so checks that span
// several rows (the last leader) see a stable picture.
func (d *DB) lockWorkspace(ctx context.Context, tx *Tx, id string) error {
	if d.dialect.ForUpdate == "" {
		return nil
	}
	var got string
	err := tx.QueryRowContext(ctx, `SELECT id FROM workspaces WHERE id = ?`+d.dialect.ForUpdate, id).Scan(&got)
	if errors.Is(err, sql.ErrNoRows) {
		return store.ErrNotFound
	}
	return err
}

// b2i stores a flag as 0 or 1: the schema keeps flags as INTEGER so the
// same comparisons (revoked = 0) work on every engine.
func b2i(b bool) int {
	if b {
		return 1
	}
	return 0
}

// conn and Tx rewrite placeholders on the way through, so the shared code
// reads like plain database/sql.
type conn struct {
	db       *sql.DB
	numbered bool
}

func (c *conn) ExecContext(ctx context.Context, q string, args ...any) (sql.Result, error) {
	return c.db.ExecContext(ctx, rebind(q, c.numbered), args...)
}

func (c *conn) QueryContext(ctx context.Context, q string, args ...any) (*sql.Rows, error) {
	return c.db.QueryContext(ctx, rebind(q, c.numbered), args...)
}

func (c *conn) QueryRowContext(ctx context.Context, q string, args ...any) *sql.Row {
	return c.db.QueryRowContext(ctx, rebind(q, c.numbered), args...)
}

func (c *conn) BeginTx(ctx context.Context, opts *sql.TxOptions) (*Tx, error) {
	tx, err := c.db.BeginTx(ctx, opts)
	if err != nil {
		return nil, err
	}
	return &Tx{tx: tx, numbered: c.numbered}, nil
}

// Tx is a transaction with the same placeholder rewriting as the store.
type Tx struct {
	tx       *sql.Tx
	numbered bool
}

func (t *Tx) ExecContext(ctx context.Context, q string, args ...any) (sql.Result, error) {
	return t.tx.ExecContext(ctx, rebind(q, t.numbered), args...)
}

func (t *Tx) QueryContext(ctx context.Context, q string, args ...any) (*sql.Rows, error) {
	return t.tx.QueryContext(ctx, rebind(q, t.numbered), args...)
}

func (t *Tx) QueryRowContext(ctx context.Context, q string, args ...any) *sql.Row {
	return t.tx.QueryRowContext(ctx, rebind(q, t.numbered), args...)
}

func (t *Tx) Commit() error   { return t.tx.Commit() }
func (t *Tx) Rollback() error { return t.tx.Rollback() }

// rebind turns ? into $1, $2… outside quoted strings when numbered is set.
func rebind(q string, numbered bool) string {
	if !numbered || !strings.Contains(q, "?") {
		return q
	}
	var b strings.Builder
	b.Grow(len(q) + 16)
	n := 0
	quoted := false
	for i := 0; i < len(q); i++ {
		c := q[i]
		switch {
		case c == '\'':
			quoted = !quoted
			b.WriteByte(c)
		case c == '?' && !quoted:
			n++
			b.WriteByte('$')
			b.WriteString(strconv.Itoa(n))
		default:
			b.WriteByte(c)
		}
	}
	return b.String()
}

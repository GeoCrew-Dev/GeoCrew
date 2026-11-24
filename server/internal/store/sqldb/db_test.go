package sqldb

import "testing"

func TestRebind(t *testing.T) {
	q := `UPDATE comments SET body = '', note = 'why?' WHERE a = ? AND b IN (?, ?)`
	if got := rebind(q, false); got != q {
		t.Fatalf("unnumbered changed: %s", got)
	}
	want := `UPDATE comments SET body = '', note = 'why?' WHERE a = $1 AND b IN ($2, $3)`
	if got := rebind(q, true); got != want {
		t.Fatalf("numbered: %s", got)
	}
}

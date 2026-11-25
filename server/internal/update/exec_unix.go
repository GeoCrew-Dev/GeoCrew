//go:build unix

package update

import (
	"os"
	"syscall"
)

// CanRestart reports whether Exec can replace the running process.
const CanRestart = true

// Exec replaces the running process with bin, keeping its arguments and
// environment, so a supervisor sees the same process carry on.
func Exec(bin string) error {
	args := append([]string{bin}, os.Args[1:]...)
	return syscall.Exec(bin, args, os.Environ())
}

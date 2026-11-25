//go:build unix

package overview

import "syscall"

func freeBytes(dir string) int64 {
	var s syscall.Statfs_t
	if err := syscall.Statfs(dir, &s); err != nil {
		return -1
	}
	return int64(s.Bavail) * int64(s.Bsize) //nolint:unconvert // field types differ by platform
}

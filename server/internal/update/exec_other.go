//go:build !unix

package update

import "errors"

// CanRestart reports whether Exec can replace the running process.
const CanRestart = false

// Exec is not available here; the service must be restarted by its manager.
func Exec(bin string) error {
	return errors.New("请重启服务以运行新版本")
}

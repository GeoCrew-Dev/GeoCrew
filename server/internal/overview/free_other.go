//go:build !unix

package overview

func freeBytes(string) int64 { return -1 }

// Command conformance checks a GeoCrew server against protocol v0 from the
// outside, over HTTP and WebSocket only, so any implementation can run it:
//
//	go run github.com/GeoCrew-Dev/GeoCrew-Protocol/conformance@latest -server https://geo.example.org
//
// It creates a throwaway workspace (the server must offer the "create"
// capability), joins it as a second member and walks through membership,
// sync, conflicts, history, settings, attachments and the realtime channel.
// Checks for optional capabilities run only when the server declares them.
// The exit status is 1 when any check fails.
package main

import (
	"flag"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"
)

func main() {
	server := flag.String("server", "http://localhost:8080", "root address of the server under test")
	verbose := flag.Bool("v", false, "print the reason for each skipped check too")
	flag.Parse()

	s := &suite{c: &client{base: strings.TrimRight(*server, "/"), http: &http.Client{Timeout: 30 * time.Second}}}
	start := time.Now()
	results := s.run()
	failed, skipped := 0, 0
	for _, r := range results {
		switch {
		case r.err != nil:
			failed++
			fmt.Printf("✗ %s %s\n    %v\n", r.section, r.name, r.err)
		case r.skip != "":
			skipped++
			if *verbose {
				fmt.Printf("- %s %s（跳过：%s）\n", r.section, r.name, r.skip)
			}
		default:
			fmt.Printf("✓ %s %s\n", r.section, r.name)
		}
	}
	if s.ws == "" && s.caps != nil && !s.caps["create"] {
		fmt.Println("\n服务端没有声明 create（关闭了新建工作组），大部分检查需要一个新工作组，已跳过；请在允许新建的测试实例上运行。")
	}
	fmt.Printf("\n%d 项通过，%d 项失败，%d 项跳过，用时 %.1f 秒\n", len(results)-failed-skipped, failed, skipped, time.Since(start).Seconds())
	if failed > 0 {
		os.Exit(1)
	}
}

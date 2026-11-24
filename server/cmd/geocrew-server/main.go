// Command geocrew-server is the self-hosted GeoCrew team server.
package main

import (
	"flag"
	"log"
	"net/http"
	"os"

	"github.com/GeoCrew-Dev/geocrew-server/internal/api"
)

func main() {
	addr := flag.String("addr", envOr("GEOCREW_ADDR", ":8080"), "listen address")
	name := flag.String("name", envOr("GEOCREW_NAME", "GeoCrew Server"), "server display name")
	flag.Parse()

	srv := &api.Server{Name: *name, Store: api.NewStore()}
	log.Printf("geocrew-server listening on %s (protocol v%s)", *addr, api.ProtocolVersion)
	log.Fatal(http.ListenAndServe(*addr, srv.Handler()))
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

package main

import (
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"time"

	"github.com/VeryBigSad/pi-app/relay/internal/bootstrap"
	"github.com/VeryBigSad/pi-app/relay/internal/httpapi"
	"github.com/VeryBigSad/pi-app/relay/internal/registry"
)

func main() {
	address := flag.String("listen", "127.0.0.1:8080", "HTTP listen address")
	database := flag.String("registry", "relay.db", "bbolt registry path")
	bootstrapToken := flag.String("bootstrap-token-file", "/etc/pi-relay/bootstrap.token", "one-use bootstrap token path")
	flag.Parse()

	keys, err := registry.Open(*database)
	if err != nil {
		fmt.Fprintf(os.Stderr, "relay registry failed: %v\n", err)
		os.Exit(1)
	}
	defer keys.Close()
	server := &http.Server{
		Addr:              *address,
		Handler:           httpapi.New(keys, bootstrap.New(*bootstrapToken), nil).Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       90 * time.Second,
		MaxHeaderBytes:    32 << 10,
	}

	slog.Info("relay starting", "address", *address)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		fmt.Fprintf(os.Stderr, "relay server failed: %v\n", err)
		os.Exit(1)
	}
}

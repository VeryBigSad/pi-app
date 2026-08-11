package httpapi

import (
	"context"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestControlRejectsUnknownPreAuthenticationMessageFields(t *testing.T) {
	handler, keys, privateKey, _ := testServer(t)
	defer keys.Close()
	registerKey(t, handler, privateKey)
	server := httptest.NewServer(handler)
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")
	control := dial(t, wsBase+"/v1/routes/route-1/control", nil, websocket.CompressionDisabled)
	defer control.Close(websocket.StatusNormalClosure, "")
	writeText(t, control, map[string]string{"type": "route.control.begin", "routeId": "route-1", "keyId": "key-1", "extra": "reject"})
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, _, err := control.Read(ctx); err == nil {
		t.Fatal("control accepted unknown pre-auth field")
	}
}

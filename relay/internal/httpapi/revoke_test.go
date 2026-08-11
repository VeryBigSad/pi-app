package httpapi

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/VeryBigSad/pi-app/relay/internal/auth"
	"github.com/coder/websocket"
)

func TestRevokeTerminatesLiveControlAndPendingDevice(t *testing.T) {
	handler, keys, privateKey, _ := testServer(t)
	defer keys.Close()
	registerKey(t, handler, privateKey)
	deviceKey := registerDeviceKey(t, handler, privateKey)
	server := httptest.NewServer(handler)
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")
	control := establishControl(t, wsBase, privateKey, "key-1")
	defer control.Close(websocket.StatusNormalClosure, "")
	deviceHeader := http.Header{"X-Relay-Proof": []string{string(proofJSON(t, deviceKey, signedFor(t, "device-data", "", "", "device-1"), false))}}
	device := dial(t, wsBase+"/v1/routes/route-1/data", deviceHeader, websocket.CompressionDisabled)
	defer device.Close(websocket.StatusNormalClosure, "")
	var notice map[string]any
	readText(t, control, &notice)
	if notice["type"] != "route.notice" {
		t.Fatalf("notice = %#v", notice)
	}
	if status := postRevoke(t, handler, privateKey, "device-1"); status != http.StatusNoContent {
		t.Fatalf("revoke device status = %d", status)
	}
	expectClosed(t, device, "pending device data")
	if status := postRevoke(t, handler, privateKey, "key-1"); status != http.StatusNoContent {
		t.Fatalf("revoke mac status = %d", status)
	}
	expectClosed(t, control, "control session")
}

func TestRevokeTerminatesActiveSplice(t *testing.T) {
	handler, keys, privateKey, _ := testServer(t)
	defer keys.Close()
	registerKey(t, handler, privateKey)
	deviceKey := registerDeviceKey(t, handler, privateKey)
	server := httptest.NewServer(handler)
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")
	control := establishControl(t, wsBase, privateKey, "key-1")
	defer control.Close(websocket.StatusNormalClosure, "")
	deviceHeader := http.Header{"X-Relay-Proof": []string{string(proofJSON(t, deviceKey, signedFor(t, "device-data", "", "", "device-1"), false))}}
	device := dial(t, wsBase+"/v1/routes/route-1/data", deviceHeader, websocket.CompressionDisabled)
	defer device.Close(websocket.StatusNormalClosure, "")
	var notice struct {
		RendezvousID string `json:"rendezvousId"`
		Nonce        string `json:"nonce"`
	}
	readText(t, control, &notice)
	macHeader := http.Header{"X-Relay-Proof": []string{string(proofJSON(t, privateKey, signed(t, "mac-data", notice.RendezvousID, notice.Nonce), false))}}
	mac := dial(t, wsBase+"/v1/routes/route-1/data", macHeader, websocket.CompressionDisabled)
	defer mac.Close(websocket.StatusNormalClosure, "")
	writeBinary(t, device, []byte("before-revoke"))
	if actual := readBinary(t, mac); string(actual) != "before-revoke" {
		t.Fatalf("mac received %q", actual)
	}
	if status := postRevoke(t, handler, privateKey, "device-1"); status != http.StatusNoContent {
		t.Fatalf("revoke device status = %d", status)
	}
	expectClosed(t, device, "spliced device data")
	expectClosed(t, mac, "spliced mac data")
}

func TestRevokedKeyRejectedOnNewConnections(t *testing.T) {
	handler, keys, privateKey, _ := testServer(t)
	defer keys.Close()
	registerKey(t, handler, privateKey)
	if status := postRevoke(t, handler, privateKey, "key-1"); status != http.StatusNoContent {
		t.Fatalf("revoke status = %d", status)
	}
	server := httptest.NewServer(handler)
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")
	control := dial(t, wsBase+"/v1/routes/route-1/control", nil, websocket.CompressionDisabled)
	defer control.Close(websocket.StatusNormalClosure, "")
	writeText(t, control, controlBegin{Type: "route.control.begin", RouteID: "route-1", KeyID: "key-1"})
	expectClosed(t, control, "control with revoked key")
	request, err := http.NewRequest(http.MethodGet, strings.Replace(wsBase, "ws://", "http://", 1)+"/v1/routes/route-1/data", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-Relay-Proof", string(proofJSON(t, privateKey, signed(t, "mac-data", "rendezvous", ""), false)))
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("revoked key data status = %d", response.StatusCode)
	}
}

func establishControl(t *testing.T, wsBase string, privateKey *ecdsa.PrivateKey, keyID string) *websocket.Conn {
	t.Helper()
	control := dial(t, wsBase+"/v1/routes/route-1/control", nil, websocket.CompressionDisabled)
	writeText(t, control, controlBegin{Type: "route.control.begin", RouteID: "route-1", KeyID: keyID})
	var challenge struct {
		Type   string      `json:"type"`
		Signed auth.Signed `json:"signed"`
	}
	readText(t, control, &challenge)
	if challenge.Type != "route.challenge" || challenge.Signed.KeyID != keyID {
		t.Fatalf("challenge = %#v", challenge)
	}
	writeText(t, control, json.RawMessage(proofJSON(t, privateKey, challenge.Signed, true)))
	var ready struct {
		Type string `json:"type"`
	}
	readText(t, control, &ready)
	if ready.Type != "route.control.ready" {
		t.Fatalf("ready = %#v", ready)
	}
	return control
}

func postRevoke(t *testing.T, handler http.Handler, key *ecdsa.PrivateKey, keyID string) int {
	t.Helper()
	body := jsonBody(t, revokeRequest{KeyID: keyID})
	request := httptest.NewRequest(http.MethodPost, "/v1/routes/route-1/revoke", bytes.NewReader(body))
	request.Header.Set("X-Relay-Proof", string(proofJSON(t, key, signed(t, "route-admin", "", ""), false)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response.Code
}

func expectClosed(t *testing.T, conn *websocket.Conn, what string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if _, _, err := conn.Read(ctx); err == nil {
		t.Fatalf("%s still open", what)
	}
}

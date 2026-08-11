package httpapi

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/VeryBigSad/pi-app/relay/internal/registry"
	"github.com/coder/websocket"
)

func TestRotateOverlapAcceptsEitherKeyAndRetiresOld(t *testing.T) {
	server, keys, privateKey, _ := newTestServer(t)
	defer keys.Close()
	server.sweepInterval = 20 * time.Millisecond
	handler := server.Handler()
	registerKey(t, handler, privateKey)
	newKey := generateKey(t)
	if status := postRotate(t, handler, privateKey, "key-2", newKey, 3600); status != http.StatusCreated {
		t.Fatalf("rotate status = %d", status)
	}
	if status := postRotate(t, handler, privateKey, "key-2", newKey, 3600); status != http.StatusConflict {
		t.Fatalf("duplicate rotate status = %d", status)
	}
	if status := postRegisterDevice(t, handler, newKey, "key-2", "device-new"); status != http.StatusCreated {
		t.Fatalf("new key admin status = %d", status)
	}
	if status := postRegisterDevice(t, handler, privateKey, "key-1", "device-old"); status != http.StatusCreated {
		t.Fatalf("old key admin status = %d", status)
	}
	live := httptest.NewServer(handler)
	defer live.Close()
	wsBase := "ws" + strings.TrimPrefix(live.URL, "http")
	control := establishControl(t, wsBase, privateKey, "key-1")
	defer control.Close(websocket.StatusNormalClosure, "")
	keys.SetClock(func() time.Time { return time.Now().Add(2 * time.Hour) })
	expectClosed(t, control, "control on retired key")
	if status := postRegisterDevice(t, handler, privateKey, "key-1", "device-late"); status != http.StatusUnauthorized {
		t.Fatalf("retired key admin status = %d", status)
	}
	if status := postRegisterDevice(t, handler, newKey, "key-2", "device-late"); status != http.StatusCreated {
		t.Fatalf("successor key admin status = %d", status)
	}
	successorControl := establishControl(t, wsBase, newKey, "key-2")
	defer successorControl.Close(websocket.StatusNormalClosure, "")
}

func TestRotateRejectsNonMacProofAndUnknownRoute(t *testing.T) {
	server, keys, privateKey, _ := newTestServer(t)
	defer keys.Close()
	handler := server.Handler()
	registerKey(t, handler, privateKey)
	deviceKey := registerDeviceKey(t, handler, privateKey)
	if status := postRotate(t, handler, deviceKey, "key-2", generateKey(t), 3600); status != http.StatusUnauthorized {
		t.Fatalf("device rotate status = %d", status)
	}
	if status := postRotate(t, handler, privateKey, "key-2", generateKey(t), 0); status != http.StatusCreated {
		t.Fatalf("default overlap rotate status = %d", status)
	}
	key, err := keys.Lookup("route-1", "key-1")
	if err != nil || key.RetiresAt.IsZero() {
		t.Fatalf("predecessor not scheduled for retirement: %#v, %v", key, err)
	}
	if status := postRevoke(t, handler, privateKey, "key-2"); status != http.StatusNoContent {
		t.Fatalf("revoke successor status = %d", status)
	}
	if status := postRotate(t, handler, privateKey, "key-3", generateKey(t), int(registry.MaxRotationOverlap/time.Second)+1); status != http.StatusBadRequest {
		t.Fatalf("oversized overlap status = %d", status)
	}
}

func postRotate(t *testing.T, handler http.Handler, key *ecdsa.PrivateKey, newKeyID string, newKey *ecdsa.PrivateKey, overlapSeconds int) int {
	t.Helper()
	body := jsonBody(t, rotateRequest{KeyID: newKeyID, PublicKeySPKI: base64.RawURLEncoding.EncodeToString(marshalPublicKey(t, newKey)), OverlapSeconds: overlapSeconds})
	request := httptest.NewRequest(http.MethodPost, "/v1/routes/route-1/rotate", bytes.NewReader(body))
	request.Header.Set("X-Relay-Proof", string(proofJSON(t, key, signed(t, "route-admin", "", ""), false)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response.Code
}

func postRegisterDevice(t *testing.T, handler http.Handler, key *ecdsa.PrivateKey, keyID, deviceID string) int {
	t.Helper()
	body := jsonBody(t, registerRequest{KeyID: deviceID, PublicKeySPKI: base64.RawURLEncoding.EncodeToString(marshalPublicKey(t, generateKey(t)))})
	request := httptest.NewRequest(http.MethodPost, "/v1/routes/route-1/devices", bytes.NewReader(body))
	request.Header.Set("X-Relay-Proof", string(proofJSON(t, key, signedFor(t, "route-admin", "", "", keyID), false)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response.Code
}

func generateKey(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return key
}

package httpapi

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/VeryBigSad/pi-app/relay/internal/auth"
	"github.com/VeryBigSad/pi-app/relay/internal/bootstrap"
	"github.com/VeryBigSad/pi-app/relay/internal/registry"
	"github.com/coder/websocket"
	"github.com/gowebpki/jcs"
)

func TestRegisterConsumesBootstrapAndRejectsReuse(t *testing.T) {
	handler, keys, privateKey, tokenPath := testServer(t)
	defer keys.Close()
	der := marshalPublicKey(t, privateKey)
	payload := jsonBody(t, registerRequest{KeyID: "key-1", PublicKeySPKI: base64.RawURLEncoding.EncodeToString(der)})
	request := httptest.NewRequest(http.MethodPost, "/v1/routes/route-1/register", bytes.NewReader(payload))
	request.Header.Set("X-Relay-Bootstrap", "bootstrap-token")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusCreated {
		t.Fatalf("registration status = %d, body = %s", response.Code, response.Body.String())
	}
	if _, err := os.Stat(tokenPath); !os.IsNotExist(err) {
		t.Fatalf("bootstrap token remains: %v", err)
	}
	request = httptest.NewRequest(http.MethodPost, "/v1/routes/route-2/register", bytes.NewReader(payload))
	request.Header.Set("X-Relay-Bootstrap", "bootstrap-token")
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("reused bootstrap status = %d", response.Code)
	}
}

func TestControlDataRendezvousSplicesOpaqueBinaryWithoutCompression(t *testing.T) {
	handler, keys, privateKey, _ := testServer(t)
	defer keys.Close()
	registerKey(t, handler, privateKey)
	deviceKey := registerDeviceKey(t, handler, privateKey)
	server := httptest.NewServer(handler)
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")
	control := dial(t, wsBase+"/v1/routes/route-1/control", nil, websocket.CompressionContextTakeover)
	defer control.Close(websocket.StatusNormalClosure, "")
	writeText(t, control, controlBegin{Type: "route.control.begin", RouteID: "route-1", KeyID: "key-1"})
	var challenge struct {
		Type   string      `json:"type"`
		Signed auth.Signed `json:"signed"`
	}
	readText(t, control, &challenge)
	if challenge.Type != "route.challenge" || challenge.Signed.Audience != "control" {
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
	deviceSigned := signedFor(t, "device-data", "", "", "device-1")
	deviceHeader := http.Header{"X-Relay-Proof": []string{string(proofJSON(t, deviceKey, deviceSigned, false))}}
	device := dial(t, wsBase+"/v1/routes/route-1/data", deviceHeader, websocket.CompressionContextTakeover)
	defer device.Close(websocket.StatusNormalClosure, "")
	var notice struct {
		Type         string `json:"type"`
		RendezvousID string `json:"rendezvousId"`
		Nonce        string `json:"nonce"`
		ExpiresAt    string `json:"expiresAt"`
		Mode         string `json:"mode"`
	}
	readText(t, control, &notice)
	if notice.Type != "route.notice" || notice.RendezvousID == "" || notice.Mode != "normal" {
		t.Fatalf("notice = %#v", notice)
	}
	macSigned := signed(t, "mac-data", notice.RendezvousID, notice.Nonce)
	macHeader := http.Header{"X-Relay-Proof": []string{string(proofJSON(t, privateKey, macSigned, false))}}
	mac := dial(t, wsBase+"/v1/routes/route-1/data", macHeader, websocket.CompressionContextTakeover)
	defer mac.Close(websocket.StatusNormalClosure, "")
	writeBinary(t, device, []byte("from-device"))
	if actual := readBinary(t, mac); string(actual) != "from-device" {
		t.Fatalf("mac received %q", actual)
	}
	writeBinary(t, mac, []byte("from-mac"))
	if actual := readBinary(t, device); string(actual) != "from-mac" {
		t.Fatalf("device received %q", actual)
	}
}

func TestDataProofReplayAndWrongAudienceFail(t *testing.T) {
	handler, keys, privateKey, _ := testServer(t)
	defer keys.Close()
	registerKey(t, handler, privateKey)
	deviceKey := registerDeviceKey(t, handler, privateKey)
	server := httptest.NewServer(handler)
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")
	controlProof := signed(t, "control", "", "")
	request, err := http.NewRequest(http.MethodGet, strings.Replace(wsBase, "ws://", "http://", 1)+"/v1/routes/route-1/data", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("X-Relay-Proof", string(proofJSON(t, privateKey, controlProof, false)))
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("control proof data status = %d", response.StatusCode)
	}
	response.Body.Close()
	macAsDevice := signed(t, "device-data", "", "")
	_, response, err = websocket.Dial(context.Background(), wsBase+"/v1/routes/route-1/data", &websocket.DialOptions{HTTPHeader: http.Header{"X-Relay-Proof": []string{string(proofJSON(t, privateKey, macAsDevice, false))}}})
	if err == nil || response == nil || response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("mac device-role escalation = %v, response = %#v", err, response)
	}
	deviceAsMac := signedFor(t, "mac-data", "rendezvous", "", "device-1")
	_, response, err = websocket.Dial(context.Background(), wsBase+"/v1/routes/route-1/data", &websocket.DialOptions{HTTPHeader: http.Header{"X-Relay-Proof": []string{string(proofJSON(t, deviceKey, deviceAsMac, false))}}})
	if err == nil || response == nil || response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("device mac-role escalation = %v, response = %#v", err, response)
	}
	deviceProof := signedFor(t, "device-data", "", "", "device-1")
	header := http.Header{"X-Relay-Proof": []string{string(proofJSON(t, deviceKey, deviceProof, false))}}
	device := dial(t, wsBase+"/v1/routes/route-1/data", header, websocket.CompressionDisabled)
	defer device.Close(websocket.StatusNormalClosure, "")
	_, response, err = websocket.Dial(context.Background(), wsBase+"/v1/routes/route-1/data", &websocket.DialOptions{HTTPHeader: header})
	if err == nil || response == nil || response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("replayed proof result = %v, response = %#v", err, response)
	}
}

func testServer(t *testing.T) (http.Handler, *registry.Registry, *ecdsa.PrivateKey, string) {
	t.Helper()
	server, keys, privateKey, tokenPath := newTestServer(t)
	return server.Handler(), keys, privateKey, tokenPath
}

func newTestServer(t *testing.T) (*Server, *registry.Registry, *ecdsa.PrivateKey, string) {
	t.Helper()
	dir := t.TempDir()
	keys, err := registry.Open(filepath.Join(dir, "relay.db"))
	if err != nil {
		t.Fatal(err)
	}
	tokenPath := filepath.Join(dir, "bootstrap.token")
	if err := os.WriteFile(tokenPath, []byte("bootstrap-token\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return New(keys, bootstrap.New(tokenPath), nil), keys, privateKey, tokenPath
}

func registerKey(t *testing.T, handler http.Handler, privateKey *ecdsa.PrivateKey) {
	t.Helper()
	der := marshalPublicKey(t, privateKey)
	body := jsonBody(t, registerRequest{KeyID: "key-1", PublicKeySPKI: base64.RawURLEncoding.EncodeToString(der)})
	request := httptest.NewRequest(http.MethodPost, "/v1/routes/route-1/register", bytes.NewReader(body))
	request.Header.Set("X-Relay-Bootstrap", "bootstrap-token")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusCreated {
		t.Fatalf("register key status = %d: %s", response.Code, response.Body.String())
	}
}

func registerDeviceKey(t *testing.T, handler http.Handler, macKey *ecdsa.PrivateKey) *ecdsa.PrivateKey {
	t.Helper()
	deviceKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	body := jsonBody(t, registerRequest{KeyID: "device-1", PublicKeySPKI: base64.RawURLEncoding.EncodeToString(marshalPublicKey(t, deviceKey))})
	request := httptest.NewRequest(http.MethodPost, "/v1/routes/route-1/devices", bytes.NewReader(body))
	request.Header.Set("X-Relay-Proof", string(proofJSON(t, macKey, signed(t, "route-admin", "", ""), false)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusCreated {
		t.Fatalf("register device status = %d: %s", response.Code, response.Body.String())
	}
	return deviceKey
}

func signed(t *testing.T, audience, rendezvousID, nonce string) auth.Signed {
	return signedFor(t, audience, rendezvousID, nonce, "key-1")
}

func signedFor(t *testing.T, audience, rendezvousID, nonce, keyID string) auth.Signed {
	t.Helper()
	if nonce == "" {
		bytes := make([]byte, auth.NonceBytes)
		if _, err := rand.Read(bytes); err != nil {
			t.Fatal(err)
		}
		nonce = base64.RawURLEncoding.EncodeToString(bytes)
	}
	return auth.Signed{Audience: audience, RouteID: "route-1", KeyID: keyID, Nonce: nonce, RendezvousID: rendezvousID, ExpiresAt: time.Now().Add(20 * time.Second).UTC().Format(time.RFC3339Nano)}
}

func proofJSON(t *testing.T, privateKey *ecdsa.PrivateKey, value auth.Signed, control bool) []byte {
	t.Helper()
	rawSigned, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	canonical, err := jcs.Transform(rawSigned)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(canonical)
	signature, err := ecdsa.SignASN1(rand.Reader, privateKey, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	proof := map[string]any{"signed": json.RawMessage(rawSigned), "signature": base64.RawURLEncoding.EncodeToString(signature)}
	if control {
		proof["type"] = "route.proof"
	}
	return jsonBody(t, proof)
}

func dial(t *testing.T, rawURL string, header http.Header, compression websocket.CompressionMode) *websocket.Conn {
	t.Helper()
	conn, response, err := websocket.Dial(context.Background(), rawURL, &websocket.DialOptions{HTTPHeader: header, CompressionMode: compression})
	if err != nil {
		t.Fatalf("dial %s: %v", rawURL, err)
	}
	if response.Header.Get("Sec-WebSocket-Extensions") != "" {
		t.Fatalf("compression negotiated: %q", response.Header.Get("Sec-WebSocket-Extensions"))
	}
	return conn
}

func writeText(t *testing.T, conn *websocket.Conn, value any) {
	t.Helper()
	if err := conn.Write(context.Background(), websocket.MessageText, jsonBody(t, value)); err != nil {
		t.Fatal(err)
	}
}

func readText(t *testing.T, conn *websocket.Conn, value any) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	kind, raw, err := conn.Read(ctx)
	if err != nil || kind != websocket.MessageText || json.Unmarshal(raw, value) != nil {
		t.Fatalf("read text kind=%v raw=%q err=%v", kind, raw, err)
	}
}

func writeBinary(t *testing.T, conn *websocket.Conn, value []byte) {
	t.Helper()
	if err := conn.Write(context.Background(), websocket.MessageBinary, value); err != nil {
		t.Fatal(err)
	}
}

func readBinary(t *testing.T, conn *websocket.Conn) []byte {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	kind, reader, err := conn.Reader(ctx)
	if err != nil || kind != websocket.MessageBinary {
		t.Fatalf("read binary kind=%v err=%v", kind, err)
	}
	value, err := io.ReadAll(reader)
	if err != nil {
		t.Fatal(err)
	}
	return value
}

func marshalPublicKey(t *testing.T, privateKey *ecdsa.PrivateKey) []byte {
	t.Helper()
	der, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return der
}

func jsonBody(t *testing.T, value any) []byte {
	t.Helper()
	body, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return body
}

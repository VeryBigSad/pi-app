package httpapi

import (
	"bytes"
	"crypto/ecdsa"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/coder/websocket"
)

func TestProvisionalPairingOneShotExchange(t *testing.T) {
	handler, keys, privateKey, _ := testServer(t)
	defer keys.Close()
	registerKey(t, handler, privateKey)
	if status := openPairing(t, handler, ""); status != http.StatusUnauthorized {
		t.Fatalf("unauthenticated pairing status = %d", status)
	}
	status, handle := openPairingOK(t, handler, privateKey)
	if status != http.StatusCreated || handle.PairingID == "" || handle.Secret == "" || handle.ExpiresAt == "" {
		t.Fatalf("pairing open = %d, %#v", status, handle)
	}
	if status, _ := openPairingOK(t, handler, privateKey); status != http.StatusConflict {
		t.Fatalf("second pairing status = %d", status)
	}
	if status := postPairingReply(t, handler, privateKey, handle.PairingID, "cert-bytes"); status != http.StatusNotFound {
		t.Fatalf("reply before request status = %d", status)
	}
	server := httptest.NewServer(handler)
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")
	control := establishControl(t, wsBase, privateKey, "key-1")
	defer control.Close(websocket.StatusNormalClosure, "")
	if status := putPairingRequest(t, handler, handle.PairingID, "wrong-secret", "csr-bytes"); status != http.StatusNotFound {
		t.Fatalf("wrong secret status = %d", status)
	}
	if status := putPairingRequest(t, handler, handle.PairingID, handle.Secret, "csr-bytes"); status != http.StatusAccepted {
		t.Fatalf("request status = %d", status)
	}
	var notice map[string]string
	readText(t, control, &notice)
	if notice["type"] != "pairing.request" || notice["pairingId"] != handle.PairingID {
		t.Fatalf("pairing notice = %#v", notice)
	}
	if status := putPairingRequest(t, handler, handle.PairingID, handle.Secret, "csr-bytes"); status != http.StatusConflict {
		t.Fatalf("duplicate request status = %d", status)
	}
	status, message := getPairingRequest(t, handler, privateKey, handle.PairingID)
	if status != http.StatusOK || message != "csr-bytes" {
		t.Fatalf("mac request read = %d, %q", status, message)
	}
	if status := postPairingReply(t, handler, privateKey, handle.PairingID, "cert-bytes"); status != http.StatusAccepted {
		t.Fatalf("reply status = %d", status)
	}
	if status := postPairingReply(t, handler, privateKey, handle.PairingID, "cert-again"); status != http.StatusConflict {
		t.Fatalf("duplicate reply status = %d", status)
	}
	if status, _ := getPairingReply(t, handler, handle.PairingID, "wrong-secret"); status != http.StatusNotFound {
		t.Fatalf("wrong reply secret status = %d", status)
	}
	status, reply := getPairingReply(t, handler, handle.PairingID, handle.Secret)
	if status != http.StatusOK || reply != "cert-bytes" {
		t.Fatalf("device reply read = %d, %q", status, reply)
	}
	if status, _ := getPairingReply(t, handler, handle.PairingID, handle.Secret); status != http.StatusNotFound {
		t.Fatalf("exchange survived delivery, status = %d", status)
	}
	if status, _ := getPairingRequest(t, handler, privateKey, handle.PairingID); status != http.StatusNotFound {
		t.Fatalf("request readable after close, status = %d", status)
	}
}

func TestPairingUnknownRouteAndIDAreNotFound(t *testing.T) {
	handler, keys, privateKey, _ := testServer(t)
	defer keys.Close()
	registerKey(t, handler, privateKey)
	if status := putPairingRequest(t, handler, "missing", "secret", "csr"); status != http.StatusNotFound {
		t.Fatalf("unknown pairing status = %d", status)
	}
	if status, _ := getPairingRequest(t, handler, privateKey, "missing"); status != http.StatusNotFound {
		t.Fatalf("unknown pairing read status = %d", status)
	}
}

type pairingHandle struct {
	PairingID string `json:"pairingId"`
	Secret    string `json:"secret"`
	ExpiresAt string `json:"expiresAt"`
}

func openPairing(t *testing.T, handler http.Handler, proof string) int {
	t.Helper()
	request := httptest.NewRequest(http.MethodPost, "/v1/routes/route-1/pairing", nil)
	if proof != "" {
		request.Header.Set("X-Relay-Proof", proof)
	}
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response.Code
}

func openPairingOK(t *testing.T, handler http.Handler, key *ecdsa.PrivateKey) (int, pairingHandle) {
	t.Helper()
	request := httptest.NewRequest(http.MethodPost, "/v1/routes/route-1/pairing", nil)
	request.Header.Set("X-Relay-Proof", string(proofJSON(t, key, signed(t, "route-admin", "", ""), false)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	var handle pairingHandle
	if response.Code == http.StatusCreated {
		if err := json.Unmarshal(response.Body.Bytes(), &handle); err != nil {
			t.Fatal(err)
		}
	}
	return response.Code, handle
}

func putPairingRequest(t *testing.T, handler http.Handler, pairingID, secret, message string) int {
	t.Helper()
	body := jsonBody(t, pairingMessageRequest{Message: base64.RawURLEncoding.EncodeToString([]byte(message))})
	request := httptest.NewRequest(http.MethodPut, "/v1/routes/route-1/pairing/"+pairingID, bytes.NewReader(body))
	request.Header.Set("X-Relay-Pairing-Secret", secret)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response.Code
}

func getPairingRequest(t *testing.T, handler http.Handler, key *ecdsa.PrivateKey, pairingID string) (int, string) {
	t.Helper()
	request := httptest.NewRequest(http.MethodGet, "/v1/routes/route-1/pairing/"+pairingID, nil)
	request.Header.Set("X-Relay-Proof", string(proofJSON(t, key, signed(t, "route-admin", "", ""), false)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response.Code, decodeMessage(t, response)
}

func postPairingReply(t *testing.T, handler http.Handler, key *ecdsa.PrivateKey, pairingID, message string) int {
	t.Helper()
	body := jsonBody(t, pairingMessageRequest{Message: base64.RawURLEncoding.EncodeToString([]byte(message))})
	request := httptest.NewRequest(http.MethodPost, "/v1/routes/route-1/pairing/"+pairingID+"/reply", bytes.NewReader(body))
	request.Header.Set("X-Relay-Proof", string(proofJSON(t, key, signed(t, "route-admin", "", ""), false)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response.Code
}

func getPairingReply(t *testing.T, handler http.Handler, pairingID, secret string) (int, string) {
	t.Helper()
	request := httptest.NewRequest(http.MethodGet, "/v1/routes/route-1/pairing/"+pairingID+"/reply", nil)
	request.Header.Set("X-Relay-Pairing-Secret", secret)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response.Code, decodeMessage(t, response)
}

func decodeMessage(t *testing.T, response *httptest.ResponseRecorder) string {
	t.Helper()
	if response.Code != http.StatusOK {
		return ""
	}
	var body struct {
		Message string `json:"message"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	message, err := base64.RawURLEncoding.DecodeString(body.Message)
	if err != nil {
		t.Fatal(err)
	}
	return string(message)
}

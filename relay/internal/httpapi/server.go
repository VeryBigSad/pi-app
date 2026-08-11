// Package httpapi exposes the relay's bounded control and opaque data endpoints.
package httpapi

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/VeryBigSad/pi-app/relay/internal/auth"
	"github.com/VeryBigSad/pi-app/relay/internal/bootstrap"
	"github.com/VeryBigSad/pi-app/relay/internal/pairing"
	"github.com/VeryBigSad/pi-app/relay/internal/registry"
	"github.com/VeryBigSad/pi-app/relay/internal/rendezvous"
	"github.com/coder/websocket"
)

const (
	maxRegisterBody = 4 << 10
	maxPairingBody  = 24 << 10
	maxDataMessage  = 64 << 10
)

type Server struct {
	registry      *registry.Registry
	bootstrap     *bootstrap.TokenFile
	replays       *auth.ReplayCache
	pending       *rendezvous.Store[*devicePeer]
	pairing       *pairing.Store
	controls      *controlHub
	live          *liveTracker
	sweepInterval time.Duration
	now           func() time.Time
}

type registerRequest struct {
	KeyID         string `json:"keyId"`
	PublicKeySPKI string `json:"publicKeySpki"`
}

type revokeRequest struct {
	KeyID string `json:"keyId"`
}

type rotateRequest struct {
	KeyID          string `json:"keyId"`
	PublicKeySPKI  string `json:"publicKeySpki"`
	OverlapSeconds int    `json:"overlapSeconds"`
}

type pairingMessageRequest struct {
	Message string `json:"message"`
}

type controlBegin struct {
	Type    string `json:"type"`
	RouteID string `json:"routeId"`
	KeyID   string `json:"keyId"`
}

type controlSession struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

type controlHub struct {
	mu       sync.Mutex
	sessions map[string]*controlSession
}

type devicePeer struct {
	conn  *websocket.Conn
	keyID string
}

// liveTracker tracks every live WebSocket per route key so revocation closes
// control sessions, pending device waits, and active data splices immediately.
type liveTracker struct {
	mu    sync.Mutex
	conns map[liveKey]map[*liveConn]struct{}
}

type liveKey struct {
	routeID string
	keyID   string
}

type liveConn struct {
	conn   *websocket.Conn
	closed chan struct{}
}

func New(registry *registry.Registry, bootstrapToken *bootstrap.TokenFile, now func() time.Time) *Server {
	if now == nil {
		now = time.Now
	}
	return &Server{
		registry: registry, bootstrap: bootstrapToken, replays: auth.NewReplayCache(now), pending: rendezvous.New[*devicePeer](now), pairing: pairing.New(now), controls: &controlHub{sessions: make(map[string]*controlSession)}, live: newLiveTracker(), sweepInterval: 30 * time.Second, now: now,
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("POST /v1/routes/{route}/register", s.register)
	mux.HandleFunc("POST /v1/routes/{route}/devices", s.registerDevice)
	mux.HandleFunc("POST /v1/routes/{route}/revoke", s.revoke)
	mux.HandleFunc("POST /v1/routes/{route}/rotate", s.rotate)
	mux.HandleFunc("POST /v1/routes/{route}/pairing", s.openPairing)
	mux.HandleFunc("PUT /v1/routes/{route}/pairing/{pairing}", s.submitPairingRequest)
	mux.HandleFunc("GET /v1/routes/{route}/pairing/{pairing}", s.pairingRequest)
	mux.HandleFunc("POST /v1/routes/{route}/pairing/{pairing}/reply", s.submitPairingReply)
	mux.HandleFunc("GET /v1/routes/{route}/pairing/{pairing}/reply", s.pairingReply)
	mux.HandleFunc("GET /v1/routes/{route}/control", s.control)
	mux.HandleFunc("GET /v1/routes/{route}/data", s.data)
	return securityHeaders(mux)
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	_, _ = w.Write([]byte("ok\n"))
}

func (s *Server) register(w http.ResponseWriter, r *http.Request) {
	if s.bootstrap == nil || !auth.ValidID(r.PathValue("route")) {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	var request registerRequest
	if !decodeJSON(w, r, &request) || !auth.ValidID(request.KeyID) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	der, err := base64.RawURLEncoding.DecodeString(request.PublicKeySPKI)
	if err != nil || len(der) == 0 || len(der) > 1024 {
		writeError(w, http.StatusBadRequest, "invalid_key")
		return
	}
	err = s.bootstrap.Consume(r.Header.Get("X-Relay-Bootstrap"), func() error {
		return s.registry.Register(r.PathValue("route"), request.KeyID, der, registry.RoleMac)
	})
	if err != nil {
		writeError(w, registrationStatus(err), "registration_failed")
		return
	}
	w.WriteHeader(http.StatusCreated)
}

func (s *Server) registerDevice(w http.ResponseWriter, r *http.Request) {
	routeID, _, err := s.authenticateHTTP(r, "route-admin")
	if err != nil || routeID != r.PathValue("route") {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var request registerRequest
	if !decodeJSON(w, r, &request) || !auth.ValidID(request.KeyID) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	der, err := base64.RawURLEncoding.DecodeString(request.PublicKeySPKI)
	if err != nil || len(der) == 0 || len(der) > 1024 {
		writeError(w, http.StatusBadRequest, "invalid_key")
		return
	}
	if err := s.registry.Register(routeID, request.KeyID, der, registry.RoleDevice); err != nil {
		writeError(w, registrationStatus(err), "registration_failed")
		return
	}
	w.WriteHeader(http.StatusCreated)
}

func (s *Server) revoke(w http.ResponseWriter, r *http.Request) {
	routeID, _, err := s.authenticateHTTP(r, "route-admin")
	if err != nil || routeID != r.PathValue("route") {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var request revokeRequest
	if !decodeJSON(w, r, &request) || !auth.ValidID(request.KeyID) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	if err := s.registry.Revoke(routeID, request.KeyID); err != nil {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	s.live.closeKey(routeID, request.KeyID)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) rotate(w http.ResponseWriter, r *http.Request) {
	routeID, proof, err := s.authenticateHTTP(r, "route-admin")
	if err != nil || routeID != r.PathValue("route") {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var request rotateRequest
	if !decodeJSON(w, r, &request) || !auth.ValidID(request.KeyID) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	der, err := base64.RawURLEncoding.DecodeString(request.PublicKeySPKI)
	if err != nil || len(der) == 0 || len(der) > 1024 {
		writeError(w, http.StatusBadRequest, "invalid_key")
		return
	}
	overlap := time.Duration(request.OverlapSeconds) * time.Second
	if request.OverlapSeconds == 0 {
		overlap = registry.MaxRotationOverlap
	}
	if err := s.registry.RegisterSuccessor(routeID, proof.Signed.KeyID, request.KeyID, der, overlap); err != nil {
		writeError(w, rotateStatus(err), "rotation_failed")
		return
	}
	w.WriteHeader(http.StatusCreated)
}

func (s *Server) openPairing(w http.ResponseWriter, r *http.Request) {
	routeID, _, err := s.authenticateHTTP(r, "route-admin")
	if err != nil || routeID != r.PathValue("route") {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	handle, err := s.pairing.Open(routeID)
	if err == pairing.ErrBusy {
		handle, err = s.pairing.Rotate(routeID)
	}
	if err != nil {
		writeError(w, pairingStatus(err), "pairing_failed")
		return
	}
	writeResponse(w, http.StatusCreated, map[string]string{"pairingId": handle.ID, "secret": handle.Secret, "expiresAt": handle.ExpiresAt.UTC().Format(time.RFC3339Nano)})
}

func (s *Server) submitPairingRequest(w http.ResponseWriter, r *http.Request) {
	routeID, pairingID := r.PathValue("route"), r.PathValue("pairing")
	if !auth.ValidID(routeID) || !auth.ValidID(pairingID) {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	var request pairingMessageRequest
	if !decodeJSONLimit(w, r, &request, maxPairingBody) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	message, err := base64.RawURLEncoding.DecodeString(request.Message)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	if err := s.pairing.SubmitRequest(routeID, pairingID, r.Header.Get("X-Relay-Pairing-Secret"), message); err != nil {
		writeError(w, pairingStatus(err), "pairing_failed")
		return
	}
	s.controls.notify(routeID, map[string]string{"type": "pairing.request", "pairingId": pairingID})
	w.WriteHeader(http.StatusAccepted)
}

func (s *Server) pairingRequest(w http.ResponseWriter, r *http.Request) {
	routeID, _, err := s.authenticateHTTP(r, "route-admin")
	if err != nil || routeID != r.PathValue("route") {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	message, err := s.pairing.Request(routeID, r.PathValue("pairing"))
	if err != nil {
		writeError(w, pairingStatus(err), "pairing_failed")
		return
	}
	writeResponse(w, http.StatusOK, map[string]string{"message": base64.RawURLEncoding.EncodeToString(message)})
}

func (s *Server) submitPairingReply(w http.ResponseWriter, r *http.Request) {
	routeID, _, err := s.authenticateHTTP(r, "route-admin")
	if err != nil || routeID != r.PathValue("route") {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var request pairingMessageRequest
	if !decodeJSONLimit(w, r, &request, maxPairingBody) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	message, err := base64.RawURLEncoding.DecodeString(request.Message)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	if err := s.pairing.SubmitReply(routeID, r.PathValue("pairing"), message); err != nil {
		writeError(w, pairingStatus(err), "pairing_failed")
		return
	}
	w.WriteHeader(http.StatusAccepted)
}

func (s *Server) pairingReply(w http.ResponseWriter, r *http.Request) {
	routeID, pairingID := r.PathValue("route"), r.PathValue("pairing")
	if !auth.ValidID(routeID) || !auth.ValidID(pairingID) {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	message, err := s.pairing.TakeReply(routeID, pairingID, r.Header.Get("X-Relay-Pairing-Secret"))
	if err != nil {
		writeError(w, pairingStatus(err), "pairing_failed")
		return
	}
	writeResponse(w, http.StatusOK, map[string]string{"message": base64.RawURLEncoding.EncodeToString(message)})
}

func (s *Server) control(w http.ResponseWriter, r *http.Request) {
	routeID := r.PathValue("route")
	if !auth.ValidID(routeID) {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	ctx := r.Context()
	conn, err := acceptWebSocket(w, r)
	if err != nil {
		return
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	begin, err := readControl[controlBegin](ctx, conn)
	if err != nil || begin.Type != "route.control.begin" || begin.RouteID != routeID || !auth.ValidID(begin.KeyID) {
		return
	}
	key, err := s.registry.Lookup(routeID, begin.KeyID)
	if err != nil || key.Role != registry.RoleMac {
		return
	}
	publicKey, err := auth.ParseP256SPKI(key.SPKIDER)
	if err != nil {
		return
	}
	challenge, err := s.challenge("control", routeID, begin.KeyID, "")
	if err != nil || !writeJSON(ctx, conn, map[string]any{"type": "route.challenge", "signed": challenge}) {
		return
	}
	proofWire, err := readControl[json.RawMessage](ctx, conn)
	if err != nil {
		return
	}
	proof, err := auth.ParseProof(proofWire)
	if err != nil || auth.MatchChallenge(proof, challenge) != nil || s.replays.Verify(proof, publicKey, "control") != nil {
		return
	}
	live := s.live.add(routeID, begin.KeyID, conn)
	defer s.live.remove(routeID, begin.KeyID, live)
	if _, err := s.registry.Lookup(routeID, begin.KeyID); err != nil {
		return
	}
	session := &controlSession{conn: conn}
	s.controls.set(routeID, session)
	defer s.controls.delete(routeID, session)
	if !writeJSON(ctx, conn, map[string]string{"type": "route.control.ready"}) {
		return
	}
	s.keepControlAlive(ctx, conn, routeID, begin.KeyID)
}

func (s *Server) data(w http.ResponseWriter, r *http.Request) {
	routeID, proof, err := s.authenticateHTTP(r, "device-data", "mac-data")
	if err != nil || routeID != r.PathValue("route") {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if proof.Signed.Audience != "device-data" && proof.Signed.Audience != "mac-data" {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	conn, err := acceptWebSocket(w, r)
	if err != nil {
		return
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	live := s.live.add(routeID, proof.Signed.KeyID, conn)
	defer s.live.remove(routeID, proof.Signed.KeyID, live)
	if _, err := s.registry.Lookup(routeID, proof.Signed.KeyID); err != nil {
		return
	}
	if proof.Signed.Audience == "device-data" {
		s.deviceData(r.Context(), routeID, proof.Signed.KeyID, conn, live)
		return
	}
	s.macData(r.Context(), routeID, proof, conn)
}

func (s *Server) deviceData(ctx context.Context, routeID, keyID string, conn *websocket.Conn, live *liveConn) {
	notice, done, err := s.pending.Start(routeID, &devicePeer{conn: conn, keyID: keyID})
	if err != nil {
		return
	}
	if !s.controls.notice(routeID, notice) {
		s.pending.Cancel(notice.ID)
		return
	}
	timer := time.NewTimer(notice.ExpiresAt.Sub(s.now()))
	defer timer.Stop()
	select {
	case <-done:
	case <-live.closed:
		s.pending.Cancel(notice.ID)
	case <-timer.C:
		s.pending.Cancel(notice.ID)
	case <-ctx.Done():
		s.pending.Cancel(notice.ID)
	}
}

func (s *Server) macData(ctx context.Context, routeID string, proof auth.Proof, conn *websocket.Conn) {
	if proof.Signed.RendezvousID == "" {
		return
	}
	match, err := s.pending.Take(routeID, proof.Signed.RendezvousID, proof.Signed.Nonce)
	if err != nil {
		return
	}
	defer close(match.Done)
	s.spliceData(ctx, routeID, []string{proof.Signed.KeyID, match.Peer.keyID}, match.Peer.conn, conn)
}

// spliceData splices a data tunnel and ends it once either participant's key
// is revoked or retired, even if no bytes are flowing.
func (s *Server) spliceData(ctx context.Context, routeID string, keyIDs []string, left, right *websocket.Conn) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	go s.retirementWatch(ctx, routeID, keyIDs, cancel)
	splice(ctx, left, right)
}

func (s *Server) retirementWatch(ctx context.Context, routeID string, keyIDs []string, cancel context.CancelFunc) {
	ticker := time.NewTicker(s.sweepInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			for _, keyID := range keyIDs {
				if _, err := s.registry.Lookup(routeID, keyID); err != nil {
					cancel()
					return
				}
			}
		}
	}
}

func (s *Server) authenticateHTTP(r *http.Request, audiences ...string) (string, auth.Proof, error) {
	raw := []byte(r.Header.Get("X-Relay-Proof"))
	proof, err := auth.ParseProof(raw)
	if err != nil {
		s.logAuthFailure(r, "parse", err)
		return "", auth.Proof{}, err
	}
	matchedAudience := false
	for _, audience := range audiences {
		if proof.Signed.Audience == audience {
			matchedAudience = true
			break
		}
	}
	if !matchedAudience {
		return "", auth.Proof{}, auth.ErrAudience
	}
	key, err := s.registry.Lookup(proof.Signed.RouteID, proof.Signed.KeyID)
	if err != nil {
		s.logAuthFailure(r, "lookup", err)
		return "", auth.Proof{}, err
	}
	if key.Role != roleForAudience(proof.Signed.Audience) {
		s.logAuthFailure(r, "role", auth.ErrAudience)
		return "", auth.Proof{}, auth.ErrAudience
	}
	publicKey, err := auth.ParseP256SPKI(key.SPKIDER)
	if err != nil {
		s.logAuthFailure(r, "spki", err)
		return "", auth.Proof{}, err
	}
	if err := s.replays.Verify(proof, publicKey, proof.Signed.Audience); err != nil {
		s.logAuthFailure(r, "verify", err)
		return "", auth.Proof{}, err
	}
	return proof.Signed.RouteID, proof, nil
}

func (s *Server) logAuthFailure(r *http.Request, stage string, err error) {
	log.Printf("auth failure path=%s stage=%s error=%v", r.URL.Path, stage, err)
}

func roleForAudience(audience string) registry.Role {
	if audience == "device-data" {
		return registry.RoleDevice
	}
	return registry.RoleMac
}

func (s *Server) challenge(audience, routeID, keyID, rendezvousID string) (auth.Signed, error) {
	nonceBytes := make([]byte, auth.NonceBytes)
	if _, err := rand.Read(nonceBytes); err != nil {
		return auth.Signed{}, err
	}
	nonce, err := auth.NewNonce(nonceBytes)
	if err != nil {
		return auth.Signed{}, err
	}
	return auth.Signed{Audience: audience, RouteID: routeID, KeyID: keyID, Nonce: nonce, ExpiresAt: s.now().Add(auth.MaxProofLifetime).UTC().Format(time.RFC3339Nano), RendezvousID: rendezvousID}, nil
}

func (s *Server) keepControlAlive(ctx context.Context, conn *websocket.Conn, routeID, keyID string) {
	var lastPong atomic.Int64
	lastPong.Store(s.now().UnixNano())
	conn.SetReadLimit(maxDataMessage)
	readCtx := conn.CloseRead(ctx)
	ticker := time.NewTicker(s.sweepInterval)
	defer ticker.Stop()
	for {
		select {
		case <-readCtx.Done():
			return
		case <-ticker.C:
			if _, err := s.registry.Lookup(routeID, keyID); err != nil {
				return
			}
			if s.now().Sub(time.Unix(0, lastPong.Load())) > 90*time.Second {
				return
			}
			pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := conn.Ping(pingCtx)
			cancel()
			if err != nil {
				return
			}
			lastPong.Store(s.now().UnixNano())
		}
	}
}

func newLiveTracker() *liveTracker {
	return &liveTracker{conns: make(map[liveKey]map[*liveConn]struct{})}
}

func (t *liveTracker) add(routeID, keyID string, conn *websocket.Conn) *liveConn {
	live := &liveConn{conn: conn, closed: make(chan struct{})}
	key := liveKey{routeID: routeID, keyID: keyID}
	t.mu.Lock()
	if t.conns[key] == nil {
		t.conns[key] = make(map[*liveConn]struct{})
	}
	t.conns[key][live] = struct{}{}
	t.mu.Unlock()
	return live
}

func (t *liveTracker) remove(routeID, keyID string, live *liveConn) {
	t.mu.Lock()
	defer t.mu.Unlock()
	key := liveKey{routeID: routeID, keyID: keyID}
	set, ok := t.conns[key]
	if !ok {
		return
	}
	delete(set, live)
	if len(set) == 0 {
		delete(t.conns, key)
	}
}

func (t *liveTracker) closeKey(routeID, keyID string) {
	t.mu.Lock()
	set := t.conns[liveKey{routeID: routeID, keyID: keyID}]
	delete(t.conns, liveKey{routeID: routeID, keyID: keyID})
	t.mu.Unlock()
	for live := range set {
		close(live.closed)
		_ = live.conn.Close(websocket.StatusPolicyViolation, "key revoked")
	}
}

func (h *controlHub) set(routeID string, session *controlSession) {
	h.mu.Lock()
	old := h.sessions[routeID]
	h.sessions[routeID] = session
	h.mu.Unlock()
	if old != nil {
		_ = old.conn.Close(websocket.StatusPolicyViolation, "replaced")
	}
}

func (h *controlHub) delete(routeID string, session *controlSession) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.sessions[routeID] == session {
		delete(h.sessions, routeID)
	}
}

func (h *controlHub) notify(routeID string, value any) bool {
	h.mu.Lock()
	session := h.sessions[routeID]
	h.mu.Unlock()
	if session == nil {
		return false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return session.write(ctx, value)
}

func (h *controlHub) notice(routeID string, notice rendezvous.Notice) bool {
	return h.notify(routeID, map[string]any{"type": "route.notice", "rendezvousId": notice.ID, "nonce": notice.Nonce, "expiresAt": notice.ExpiresAt.UTC().Format(time.RFC3339Nano), "mode": "normal"})
}

func (s *controlSession) write(ctx context.Context, value any) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return writeJSON(ctx, s.conn, value)
}

func acceptWebSocket(w http.ResponseWriter, r *http.Request) (*websocket.Conn, error) {
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err == nil {
		conn.SetReadLimit(maxDataMessage)
	}
	return conn, err
}

func readControl[T any](ctx context.Context, conn *websocket.Conn) (T, error) {
	var value T
	readCtx, cancel := context.WithTimeout(ctx, auth.MaxProofLifetime)
	defer cancel()
	kind, raw, err := conn.Read(readCtx)
	if err != nil || kind != websocket.MessageText || len(raw) > auth.MaxProofBytes {
		return value, errors.New("invalid control message")
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&value); err != nil || decoder.Decode(&struct{}{}) != io.EOF {
		return value, errors.New("invalid control message")
	}
	return value, nil
}

func writeJSON(ctx context.Context, conn *websocket.Conn, value any) bool {
	raw, err := json.Marshal(value)
	return err == nil && conn.Write(ctx, websocket.MessageText, raw) == nil
}

func splice(ctx context.Context, left, right *websocket.Conn) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var once sync.Once
	closeBoth := func() {
		once.Do(func() {
			_ = left.Close(websocket.StatusNormalClosure, "")
			_ = right.Close(websocket.StatusNormalClosure, "")
			cancel()
		})
	}
	go func() {
		copyMessages(ctx, left, right)
		closeBoth()
	}()
	copyMessages(ctx, right, left)
	closeBoth()
}

func copyMessages(ctx context.Context, source, destination *websocket.Conn) {
	buffer := make([]byte, 32<<10)
	for {
		kind, reader, err := source.Reader(ctx)
		if err != nil || kind != websocket.MessageBinary {
			return
		}
		writer, err := destination.Writer(ctx, websocket.MessageBinary)
		if err != nil {
			return
		}
		_, copyErr := io.CopyBuffer(writer, reader, buffer)
		closeErr := writer.Close()
		if copyErr != nil || closeErr != nil {
			return
		}
	}
}

func decodeJSON(w http.ResponseWriter, r *http.Request, value any) bool {
	return decodeJSONLimit(w, r, value, maxRegisterBody)
}

func decodeJSONLimit(w http.ResponseWriter, r *http.Request, value any, limit int64) bool {
	if r.Body == nil || r.ContentLength > limit {
		return false
	}
	defer r.Body.Close()
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, limit))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		return false
	}
	return decoder.Decode(&struct{}{}) == io.EOF
}

func writeResponse(w http.ResponseWriter, status int, value any) {
	raw, err := json.Marshal(value)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(raw)
}

func writeError(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(`{"code":"` + code + `"}`))
}

func registrationStatus(err error) int {
	switch {
	case errors.Is(err, registry.ErrExists):
		return http.StatusConflict
	case errors.Is(err, bootstrap.ErrInvalid), errors.Is(err, bootstrap.ErrUnavailable):
		return http.StatusUnauthorized
	default:
		return http.StatusBadRequest
	}
}

func rotateStatus(err error) int {
	switch {
	case errors.Is(err, registry.ErrExists):
		return http.StatusConflict
	case errors.Is(err, registry.ErrNotFound), errors.Is(err, registry.ErrRevoked):
		return http.StatusNotFound
	default:
		return http.StatusBadRequest
	}
}

func pairingStatus(err error) int {
	switch {
	case errors.Is(err, pairing.ErrBusy), errors.Is(err, pairing.ErrDuplicate):
		return http.StatusConflict
	case errors.Is(err, pairing.ErrRateLimited):
		return http.StatusTooManyRequests
	case errors.Is(err, pairing.ErrFull):
		return http.StatusServiceUnavailable
	case errors.Is(err, pairing.ErrMessageSize):
		return http.StatusBadRequest
	default:
		return http.StatusNotFound
	}
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}

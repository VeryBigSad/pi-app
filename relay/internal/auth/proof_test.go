package auth

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"

	"github.com/gowebpki/jcs"
)

func TestProofVerifiesAndReplaysFail(t *testing.T) {
	now := time.Date(2026, 8, 9, 12, 0, 0, 0, time.UTC)
	privateKey := newKey(t)
	signed := Signed{Audience: "device-data", RouteID: "route-1", KeyID: "key-1", Nonce: nonce(t), ExpiresAt: now.Add(20 * time.Second).Format(time.RFC3339Nano)}
	proof := signedProof(t, privateKey, signed)
	cache := NewReplayCache(func() time.Time { return now })
	if err := cache.Verify(proof, &privateKey.PublicKey, "device-data"); err != nil {
		t.Fatal(err)
	}
	if err := cache.Verify(proof, &privateKey.PublicKey, "device-data"); err != ErrReplay {
		t.Fatalf("second proof error = %v, want replay", err)
	}
}

func TestProofRejectsAudienceExpirySignatureAndDuplicates(t *testing.T) {
	now := time.Date(2026, 8, 9, 12, 0, 0, 0, time.UTC)
	privateKey := newKey(t)
	valid := Signed{Audience: "control", RouteID: "route-1", KeyID: "key-1", Nonce: nonce(t), ExpiresAt: now.Add(20 * time.Second).Format(time.RFC3339Nano)}
	proof := signedProof(t, privateKey, valid)
	cache := NewReplayCache(func() time.Time { return now })
	if err := cache.Verify(proof, &privateKey.PublicKey, "mac-data"); err != ErrAudience {
		t.Fatalf("audience error = %v", err)
	}
	expired := signedProof(t, privateKey, Signed{Audience: "control", RouteID: "route-1", KeyID: "key-1", Nonce: nonce(t), ExpiresAt: now.Add(-time.Second).Format(time.RFC3339Nano)})
	if err := cache.Verify(expired, &privateKey.PublicKey, "control"); err != ErrExpired {
		t.Fatalf("expiry error = %v", err)
	}
	forged := proof
	forged.Signature[0] ^= 1
	if err := cache.Verify(forged, &privateKey.PublicKey, "control"); err != ErrInvalidSig {
		t.Fatalf("signature error = %v", err)
	}
	raw := []byte(`{"signed":{"audience":"control","routeId":"route-1","routeId":"route-2","keyId":"key-1","nonce":"` + nonce(t) + `","expiresAt":"2026-08-09T12:00:20Z"},"signature":"AA"}`)
	if _, err := ParseProof(raw); err == nil {
		t.Fatal("duplicate signed member accepted")
	}
	unknownAudience := []byte(`{"signed":{"audience":"unknown","routeId":"route-1","keyId":"key-1","nonce":"` + nonce(t) + `","expiresAt":"2026-08-09T12:00:20Z"},"signature":"AA"}`)
	if _, err := ParseProof(unknownAudience); err == nil {
		t.Fatal("unknown audience accepted")
	}
	validRaw, err := json.Marshal(map[string]any{"signed": valid, "signature": base64.RawURLEncoding.EncodeToString(proof.Signature)})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ParseProof(append(validRaw, []byte(` {}`)...)); err == nil {
		t.Fatal("trailing JSON accepted")
	}
}

func TestParseP256SPKIOnly(t *testing.T) {
	privateKey := newKey(t)
	der, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ParseP256SPKI(der); err != nil {
		t.Fatal(err)
	}
	p384, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	p384DER, err := x509.MarshalPKIXPublicKey(&p384.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ParseP256SPKI(p384DER); err != ErrInvalidKey {
		t.Fatalf("P-384 error = %v", err)
	}
}

func signedProof(t *testing.T, privateKey *ecdsa.PrivateKey, signed Signed) Proof {
	t.Helper()
	rawSigned, err := json.Marshal(signed)
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
	rawProof, err := json.Marshal(map[string]any{"signed": json.RawMessage(rawSigned), "signature": base64.RawURLEncoding.EncodeToString(signature)})
	if err != nil {
		t.Fatal(err)
	}
	proof, err := ParseProof(rawProof)
	if err != nil {
		t.Fatal(err)
	}
	return proof
}

func newKey(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return key
}

func nonce(t *testing.T) string {
	t.Helper()
	bytes := make([]byte, NonceBytes)
	if _, err := rand.Read(bytes); err != nil {
		t.Fatal(err)
	}
	return base64.RawURLEncoding.EncodeToString(bytes)
}

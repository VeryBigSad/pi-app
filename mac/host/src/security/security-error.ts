export type SecurityErrorCode =
  | "SECURITY_INVALID_INPUT"
  | "SECURITY_CSR_INVALID"
  | "SECURITY_KEYCHAIN_UNAVAILABLE"
  | "SECURITY_KEY_STORAGE_FAILED"
  | "SECURITY_REVOCATION_UNAVAILABLE"
  | "SECURITY_REVOKED"
  | "SECURITY_CEREMONY_INVALID"
  | "SECURITY_CEREMONY_EXPIRED"
  | "SECURITY_CEREMONY_REPLAY"
  | "SECURITY_WEBAUTHN_REJECTED";

export class SecurityError extends Error {
  readonly code: SecurityErrorCode;

  constructor(code: SecurityErrorCode, message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "SecurityError";
    this.code = code;
  }
}

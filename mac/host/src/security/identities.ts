import { isIP } from "node:net";
import { SecurityError } from "./security-error.js";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const DNS_LABEL = /^(?!-)[a-z0-9-]{1,63}(?<!-)$/;

export const MAC_URI_PREFIX = "urn:pimobile:mac:";
export const DEVICE_URI_PREFIX = "urn:pimobile:device:";

export function validateIdentityId(value: string, field: string): string {
  if (!UUID.test(value)) {
    throw new SecurityError("SECURITY_INVALID_INPUT", `${field} must be a canonical lowercase UUID`);
  }
  return value;
}

export function macUriIdentity(instanceId: string): string {
  return `${MAC_URI_PREFIX}${validateIdentityId(instanceId, "instanceId")}`;
}

export function deviceUriIdentity(deviceId: string): string {
  return `${DEVICE_URI_PREFIX}${validateIdentityId(deviceId, "deviceId")}`;
}

export function validateDnsName(value: string): string {
  if (value.length === 0 || value.length > 253 || value !== value.toLowerCase()) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "DNS SAN is invalid");
  }
  const labels = value.endsWith(".") ? value.slice(0, -1).split(".") : value.split(".");
  if (labels.some((label) => !DNS_LABEL.test(label))) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "DNS SAN is invalid");
  }
  return value.endsWith(".") ? value.slice(0, -1) : value;
}

export function validateIpAddress(value: string): string {
  if (isIP(value) === 0) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "IP SAN is invalid");
  }
  return value;
}

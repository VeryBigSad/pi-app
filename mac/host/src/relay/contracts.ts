export const GO_RELAY_CONTRACT_MISMATCHES = [
  {
    id: "CONTROL_READY_SCHEMA_MISMATCH",
    currentGoApi: "The relay sends route.control.ready with only type.",
    requiredContract: "route.schema.json requires routeId and keyId; the client accepts both shapes and validates bindings when present.",
  },
  {
    id: "TOP_LEVEL_ADDITIVE_FIELDS_REJECTED",
    currentGoApi: "Control input and route proofs reject unknown top-level fields.",
    requiredContract: "route.schema.json permits additive top-level properties.",
  },
  {
    id: "PROOF_TYPE_OPTIONAL_IN_GO",
    currentGoApi: "X-Relay-Proof parsing accepts an absent type field.",
    requiredContract: "route.schema.json requires type to be route.proof; the client always sends it.",
  },
  {
    id: "AUDIENCE_RENDEZVOUS_BINDING_NOT_PARSED",
    currentGoApi: "Proof parsing accepts rendezvousId on non-mac audiences and accepts mac-data without it until the data handler closes.",
    requiredContract: "route.schema.json requires rendezvousId only for mac-data.",
  },
  {
    id: "MAC_ROUTE_ROTATION_REGISTRATION_MISSING",
    currentGoApi: "Only bootstrap POST /register creates a Mac-role key; POST /devices always creates a device-role key.",
    requiredContract: "An authenticated old Mac route key can register a new Mac route key for overlap rotation.",
  },
  {
    id: "PROVISIONAL_DEVICE_DATA_MISSING",
    currentGoApi: "GET /data accepts only registry-backed device-data or mac-data X-Relay-Proof values.",
    requiredContract: "A Mac-route-signed one-use invitation can create a pairing_provisional rendezvous before a device key exists.",
  },
  {
    id: "DEVICE_DATA_CHALLENGE_MISSING",
    currentGoApi: "GET /data verifies a caller-created device-data proof directly and exposes no challenge endpoint.",
    requiredContract: "The frozen prose says normal Android data signs a fresh relay challenge.",
  },
] as const;

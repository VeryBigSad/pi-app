type PlainCloneValue = undefined | null | boolean | number | string | PlainCloneValue[] | { [key: string]: PlainCloneValue };

function dataCloneError(): DOMException {
  return new DOMException("Value cannot be cloned", "DataCloneError");
}

function clonePlain(value: PlainCloneValue, seen: WeakSet<object>): PlainCloneValue {
  if (value === undefined || value === null || typeof value === "boolean" || typeof value === "number" || typeof value === "string") return value;
  if (typeof value !== "object") throw dataCloneError();
  if (seen.has(value)) throw dataCloneError();
  seen.add(value);
  if (Array.isArray(value)) return value.map((item) => clonePlain(item, seen));
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) throw dataCloneError();
  if (Reflect.ownKeys(value).length !== Object.keys(value).length) throw dataCloneError();
  const descriptors = Object.getOwnPropertyDescriptors(value);
  if (Object.values(descriptors).some((descriptor) => !("value" in descriptor))) throw dataCloneError();
  const result = Object.create(prototype) as { [key: string]: PlainCloneValue };
  for (const [key, item] of Object.entries(value)) {
    Object.defineProperty(result, key, {
      configurable: true,
      enumerable: true,
      value: clonePlain(item, seen),
      writable: true,
    });
  }
  return result;
}

export const installedNarrowStructuredClone = typeof globalThis.structuredClone !== "function";

if (installedNarrowStructuredClone) {
  globalThis.structuredClone = ((value: PlainCloneValue) => clonePlain(value, new WeakSet())) as typeof structuredClone;
}

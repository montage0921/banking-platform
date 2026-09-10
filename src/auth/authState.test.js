import {
  buildAuthenticatedState,
  clearRememberedCustomerContext,
  decodeJwt,
  emptyAuthState,
  getRememberedCustomerContext,
  readStoredAuthState,
  rememberCustomerContext,
  writeStoredAuthState,
} from "./authState";
import { beforeEach, describe, expect, it, vi } from "vitest";

const STORAGE_KEY = "banking-app-auth";
const CUSTOMER_CONTEXT_KEY = "banking-app-customer-contexts";

function createJwt(payload) {
  const encoded = btoa(JSON.stringify(payload))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
  return `header.${encoded}.signature`;
}

function installMockLocalStorage() {
  const store = new Map();

  Object.defineProperty(window, "localStorage", {
    configurable: true,
    value: {
      getItem(key) {
        return store.has(key) ? store.get(key) : null;
      },
      setItem(key, value) {
        store.set(key, String(value));
      },
      removeItem(key) {
        store.delete(key);
      },
      clear() {
        store.clear();
      },
    },
  });
}

describe("authState helpers", () => {
  beforeEach(() => {
    installMockLocalStorage();
    vi.restoreAllMocks();
  });

  it("returns an empty object for invalid jwt payloads", () => {
    expect(decodeJwt("not-a-jwt")).toEqual({});
    expect(decodeJwt("a.bad.payload")).toEqual({});
  });

  it("reads stored auth state and normalizes roles", () => {
    window.localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        accessToken: "token",
        roles: "BANK_ADMINISTRATOR, ROLE_USER",
      }),
    );

    expect(readStoredAuthState()).toEqual({
      ...emptyAuthState,
      accessToken: "token",
      roles: ["BANK_ADMINISTRATOR", "ROLE_USER"],
    });
  });

  it("stores, retrieves, and clears remembered customer contexts", () => {
    rememberCustomerContext("subject-1", 42);

    expect(getRememberedCustomerContext("subject-1")).toBe("42");
    expect(
      JSON.parse(window.localStorage.getItem(CUSTOMER_CONTEXT_KEY)),
    ).toEqual({ "subject-1": "42" });

    clearRememberedCustomerContext("subject-1");

    expect(getRememberedCustomerContext("subject-1")).toBe("");
    expect(window.localStorage.getItem(CUSTOMER_CONTEXT_KEY)).toBeNull();
  });

  it("removes stored auth state when there is no access token", () => {
    window.localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ accessToken: "token" }),
    );

    writeStoredAuthState({ ...emptyAuthState, accessToken: "" });

    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it("builds authenticated state from jwt claims and remembered customer context", () => {
    const now = vi.spyOn(Date, "now").mockReturnValue(1_700_000_000_000);
    window.localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        ...emptyAuthState,
        subject: "subject-1",
        customerId: "stale-customer",
        roles: ["ROLE_USER"],
      }),
    );
    window.localStorage.setItem(
      CUSTOMER_CONTEXT_KEY,
      JSON.stringify({ "subject-1": "customer-99" }),
    );

    const state = buildAuthenticatedState(
      {
        accessToken: createJwt({ sub: "subject-1", roles: "ADMIN,ROLE_USER" }),
        refreshToken: "refresh-token",
        tokenType: "Bearer",
        expiresIn: 60,
      },
      "user@example.com",
    );

    expect(now).toHaveBeenCalled();
    expect(state).toEqual({
      accessToken: expect.any(String),
      refreshToken: "refresh-token",
      tokenType: "Bearer",
      expiresIn: 60,
      expiresAt: 1_700_000_060_000,
      username: "user@example.com",
      subject: "subject-1",
      roles: ["ADMIN", "ROLE_USER"],
      customerId: "customer-99",
    });
  });
});

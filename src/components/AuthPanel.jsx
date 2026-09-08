import { useEffect, useState } from "react";
import { emptyAuthState } from "../types";

const STORAGE_KEY = "banking-auth-state";

function readInitialState() {
  const saved = window.localStorage.getItem(STORAGE_KEY);
  if (!saved) {
    return emptyAuthState;
  }

  try {
    return { ...emptyAuthState, ...JSON.parse(saved) };
  } catch {
    return emptyAuthState;
  }
}

export function readAuthState() {
  return readInitialState();
}

export function AuthPanel() {
  const [formState, setFormState] = useState(readInitialState);

  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(formState));
  }, [formState]);

  return (
    <section className="panel stack">
      <div>
        <p className="eyebrow">Request identity</p>
        <h3>Auth Debug Panel</h3>
      </div>
      <p className="auth-note">
        The current backend uses a temporary auth scaffold. These values are
        sent as headers alongside the Bearer token.
      </p>
      <div className="field">
        <label htmlFor="token">Bearer token</label>
        <input
          id="token"
          value={formState.token}
          onChange={(event) =>
            setFormState((current) => ({
              ...current,
              token: event.target.value,
            }))
          }
        />
      </div>
      <div className="field">
        <label htmlFor="roles">Roles</label>
        <input
          id="roles"
          value={formState.roles}
          onChange={(event) =>
            setFormState((current) => ({
              ...current,
              roles: event.target.value,
            }))
          }
          placeholder="RETAIL_CUSTOMER or BANK_ADMINISTRATOR"
        />
      </div>
      <div className="field">
        <label htmlFor="customerId">Customer scope</label>
        <input
          id="customerId"
          value={formState.customerId}
          onChange={(event) =>
            setFormState((current) => ({
              ...current,
              customerId: event.target.value,
            }))
          }
        />
      </div>
    </section>
  );
}

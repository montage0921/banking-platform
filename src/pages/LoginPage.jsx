import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { loginUser } from "../api/auth";
import { useAuth } from "../auth/AuthContext";
import { mapAxiosError } from "../api/axiosClient";
import { emptyLoginForm, isEmailLike } from "../types";

function getPostLoginRoute(authState) {
  const isAdmin =
    authState.roles.includes("BANK_ADMINISTRATOR") ||
    authState.roles.includes("ROLE_BANK_ADMINISTRATOR");
  if (isAdmin) {
    return "/admin/customers";
  }
  // If customerId is present, redirect to their accounts page
  if (authState.customerId) {
    return `/customer/${authState.customerId}/accounts`;
  }
  // Per spec Flow B: if no customer profile found, route to customer creation
  return "/customer/create";
}

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { completeLogin } = useAuth();
  const [formState, setFormState] = useState(emptyLoginForm);
  const [error, setError] = useState(null);
  const mutation = useMutation({ mutationFn: loginUser });

  async function handleSubmit(event) {
    event.preventDefault();
    setError(null);

    if (!isEmailLike(formState.username)) {
      setError({ message: "Enter a valid email address." });
      return;
    }

    if (!formState.password) {
      setError({ message: "Password is required." });
      return;
    }

    try {
      const response = await mutation.mutateAsync(formState);
      const nextAuthState = completeLogin(response, formState.username);

      // If redirected from a protected route, go there after login
      const intended = location.state?.from?.pathname;
      if (intended) {
        navigate(intended, { replace: true });
        return;
      }

      navigate(getPostLoginRoute(nextAuthState), { replace: true });
    } catch (requestError) {
      setError(mapAxiosError(requestError));
    }
  }

  return (
    <section className="panel stack auth-panel-page">
      <div>
        <h2>Login</h2>
        <p className="muted">
          Authenticate with the merged backend and store the access token for
          protected requests.
        </p>
      </div>
      {location.state?.registered ? (
        <div className="banner success">
          Registration complete. You can now sign in.
        </div>
      ) : null}
      {new URLSearchParams(location.search).get("error") ===
      "session_expired" ? (
        <div className="banner error">
          Your session has expired. Please sign in again.
        </div>
      ) : null}
      {error ? <div className="banner error">{error.message}</div> : null}
      <form className="stack" onSubmit={handleSubmit}>
        <div className="field">
          <label htmlFor="login-username">Email</label>
          <input
            id="login-username"
            type="email"
            value={formState.username}
            onChange={(event) =>
              setFormState((current) => ({
                ...current,
                username: event.target.value,
              }))
            }
          />
        </div>
        <div className="field">
          <label htmlFor="login-password">Password</label>
          <input
            id="login-password"
            type="password"
            value={formState.password}
            onChange={(event) =>
              setFormState((current) => ({
                ...current,
                password: event.target.value,
              }))
            }
          />
        </div>
        <div className="actions">
          <button type="submit" disabled={mutation.isPending}>
            Sign In
          </button>
          <Link className="button-link subtle" to="/register">
            Register
          </Link>
          <Link className="button-link subtle" to="/password-reset">
            Password Reset
          </Link>
        </div>
      </form>
    </section>
  );
}

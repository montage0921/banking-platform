import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import {
  buildAuthenticatedState,
  clearRememberedCustomerContext,
  emptyAuthState,
  readStoredAuthState,
  rememberCustomerContext,
  writeStoredAuthState
} from './authState';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [authState, setAuthState] = useState(readStoredAuthState);

  useEffect(() => {
    writeStoredAuthState(authState);
  }, [authState]);

  useEffect(() => {
    if (authState.subject && authState.customerId) {
      rememberCustomerContext(authState.subject, authState.customerId);
    }
  }, [authState.customerId, authState.subject]);

  const value = useMemo(() => ({
    authState,
    isAuthenticated: Boolean(authState.accessToken) && (!authState.expiresAt || authState.expiresAt > Date.now()),
    isAdmin: authState.roles.includes('BANK_ADMINISTRATOR') || authState.roles.includes('ROLE_BANK_ADMINISTRATOR'),
    completeLogin(authResponse, username) {
      const nextState = buildAuthenticatedState(authResponse, username);
      // Write to localStorage synchronously so the token is immediately
      // available for any API calls made right after completeLogin() returns.
      // The useEffect will also run after re-render, which is a no-op here.
      writeStoredAuthState(nextState);
      setAuthState(nextState);
      return nextState;
    },
    rememberCustomerId(customerId) {
      setAuthState((current) => {
        const normalizedCustomerId = String(customerId);

        if (current.customerId === normalizedCustomerId) {
          return current;
        }

        return { ...current, customerId: normalizedCustomerId };
      });
    },
    clearCustomerId() {
      setAuthState((current) => {
        if (!current.customerId) {
          return current;
        }

        if (current.subject) {
          clearRememberedCustomerContext(current.subject);
        }

        return { ...current, customerId: '' };
      });
    },
    logout() {
      setAuthState(emptyAuthState);
    }
  }), [authState]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);

  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }

  return context;
}
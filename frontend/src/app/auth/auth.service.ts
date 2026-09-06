import Keycloak from 'keycloak-js';

/** Public SPA client, PKCE (see docs/decisions.md — SPA<->backend auth pattern). */
const KEYCLOAK_CONFIG = {
  url: 'http://localhost:8090',
  realm: 'claims',
  clientId: 'claims-frontend',
};

let client: Keycloak | null = null;

function keycloak(): Keycloak {
  if (!client) {
    client = new Keycloak(KEYCLOAK_CONFIG);
  }
  return client;
}

/**
 * Ensures the user is signed in, triggering the Keycloak login/registration flow when
 * needed. Resolves true once an authenticated session exists on this page.
 */
export async function ensureAuthenticated(): Promise<boolean> {
  const kc = keycloak();
  if (kc.authenticated) {
    return true;
  }
  try {
    await kc.init({ onLoad: 'login-required', pkceMethod: 'S256', flow: 'standard' });
    return !!kc.authenticated;
  } catch {
    return false;
  }
}

export function hasRole(role: string): boolean {
  const kc = keycloak();
  return !!kc.tokenParsed?.realm_access?.roles?.includes(role);
}

export async function accessToken(): Promise<string | null> {
  const kc = keycloak();
  try {
    await kc.updateToken(30);
  } catch {
    // Token could not be refreshed; the caller's request will fail auth and the user
    // can sign in again.
  }
  return kc.token ?? null;
}

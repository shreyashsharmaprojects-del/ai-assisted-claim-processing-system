import Keycloak from 'keycloak-js';
import { getAppConfig } from '../app-config';

interface SessionState {
  authenticated: boolean;
  username: string | null;
  roles: string[];
}

let client: Keycloak | null = null;
let initialized = false;
let initPromise: Promise<boolean> | null = null;
const listeners = new Set<(state: SessionState) => void>();

function keycloak(): Keycloak {
  if (!client) {
    const cfg = getAppConfig();
    client = new Keycloak({
      url: cfg.keycloakUrl,
      realm: cfg.keycloakRealm,
      clientId: cfg.keycloakClientId,
    });
  }
  return client;
}

function snapshot(): SessionState {
  const kc = keycloak();
  const parsed = kc.tokenParsed as Record<string, unknown> | undefined;
  const realmAccess = parsed?.['realm_access'] as { roles?: unknown } | undefined;
  const rawRoles = realmAccess?.roles;
  const name = parsed?.['preferred_username'];
  return {
    authenticated: !!kc.authenticated,
    username: typeof name === 'string' ? name : null,
    roles: Array.isArray(rawRoles) ? rawRoles.filter((r): r is string => typeof r === 'string') : [],
  };
}

function notify(): void {
  const state = snapshot();
  for (const listener of listeners) {
    try {
      listener(state);
    } catch {
      // A session listener must never break sign-in for everyone else.
    }
  }
}

/** Components subscribe to re-render on sign-in/sign-out without a page reload. */
export function onSessionChange(listener: (state: SessionState) => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export type { SessionState };

/**
 * Single bootstrap entry: initialise Keycloak exactly once (silent SSO — no forced
 * redirect on public pages) and start the proactive token-renewal timer. Returns true
 * when a session exists. Guards await this; components just render from sessionState().
 */
export function initSession(): Promise<boolean> {
  if (initPromise) {
    return initPromise;
  }
  initPromise = (async () => {
    const kc = keycloak();
    try {
      const authenticated = await kc.init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        flow: 'standard',
        silentCheckSsoRedirectUri: window.location.origin + '/assets/silent-check-sso.html',
        checkLoginIframe: false,
      });
      initialized = true;
      startRenewalTimer();
      notify();
      return !!authenticated;
    } catch {
      initialized = true;
      notify();
      return false;
    }
  })();
  return initPromise;
}

/**
 * Ensures the user is signed in, triggering the Keycloak login/registration flow when
 * needed. Resolves true once an authenticated session exists on this page.
 */
export async function ensureAuthenticated(): Promise<boolean> {
  const kc = keycloak();
  if (!initialized) {
    await initSession();
  }
  if (kc.authenticated) {
    return true;
  }
  try {
    await kc.login();
    return !!kc.authenticated;
  } catch {
    return false;
  }
}

export function hasRole(role: string): boolean {
  return snapshot().roles.includes(role);
}

export function isAuthenticated(): boolean {
  return keycloak().authenticated ?? false;
}

/** The signed-in user's Keycloak username (used for the top-bar identity chip). */
export function preferredUsername(): string | null {
  return snapshot().username;
}

/** A live snapshot components can poll cheaply (signals subscribe via onSessionChange). */
export function sessionState(): SessionState {
  return snapshot();
}

/** Ends the Keycloak session and returns to the app home. */
export async function logout(): Promise<void> {
  const kc = keycloak();
  stopRenewalTimer();
  if (kc.authenticated) {
    await kc.logout({ redirectUri: window.location.origin + '/' });
  } else {
    notify();
  }
}

let renewalTimer: ReturnType<typeof setInterval> | null = null;

/**
 * Proactive renewal: refresh the token every 45s when it expires within 75s, so API
 * calls never race an expiry. On refresh failure the session is dropped and listeners
 * are told — the next guarded navigation re-prompts sign-in instead of failing API
 * calls one by one.
 */
function startRenewalTimer(): void {
  stopRenewalTimer();
  renewalTimer = setInterval(() => {
    const kc = keycloak();
    if (!kc.authenticated) {
      return;
    }
    kc.updateToken(75)
      .then((refreshed) => {
        if (refreshed) {
          notify();
        }
      })
      .catch(() => {
        try {
          kc.clearToken();
        } catch {
          // Clearing a broken session must not throw inside the timer.
        }
        notify();
      });
  }, 45_000);
  if (typeof renewalTimer === 'object' && 'unref' in renewalTimer) {
    (renewalTimer as { unref(): void }).unref?.();
  }
}

function stopRenewalTimer(): void {
  if (renewalTimer !== null) {
    clearInterval(renewalTimer);
    renewalTimer = null;
  }
}

export async function accessToken(): Promise<string | null> {
  const kc = keycloak();
  if (!initialized) {
    await initSession();
  }
  try {
    // Notify listeners only when the token actually rotated: every /api call
    // flows through here, so an unconditional notify would re-trigger the
    // shell's badge fetches (which are API calls themselves) in an infinite
    // loop that floods the tab for internal sessions.
    const refreshed = await kc.updateToken(30);
    if (refreshed) {
      notify();
    }
  } catch {
    // Token could not be refreshed; the interceptor surfaces the 401 and the next
    // guarded navigation re-prompts sign-in.
  }
  return kc.token ?? null;
}

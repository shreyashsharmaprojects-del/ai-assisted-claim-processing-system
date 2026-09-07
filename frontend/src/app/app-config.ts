import { HttpClient } from '@angular/common/http';
import { catchError, of } from 'rxjs';
import { firstValueFrom } from 'rxjs';

/**
 * Runtime configuration: Keycloak coordinates are fetched from /assets/config.json at
 * bootstrap (APP_INITIALIZER) instead of being compiled in. The same production image
 * therefore runs in every environment — ops mounts the right config.json per deploy
 * (see docs/operations.md). Dev defaults point at the local compose stack, so a plain
 * `ng serve` works with no extra setup.
 */
export interface AppConfig {
  keycloakUrl: string;
  keycloakRealm: string;
  keycloakClientId: string;
}

const DEFAULTS: AppConfig = {
  keycloakUrl: 'http://localhost:8090',
  keycloakRealm: 'claims',
  keycloakClientId: 'claims-frontend',
};

let active: AppConfig = { ...DEFAULTS };

export function getAppConfig(): AppConfig {
  return active;
}

/** APP_INITIALIZER factory: resolves once config.json is loaded (or defaults kept). */
export function loadAppConfig(http: HttpClient): () => Promise<void> {
  return async () => {
    try {
      const remote = await firstValueFrom(
        http.get<Partial<AppConfig>>('/assets/config.json').pipe(catchError(() => of({}))),
      );
      active = { ...DEFAULTS, ...remote };
    } catch {
      active = { ...DEFAULTS };
    }
  };
}

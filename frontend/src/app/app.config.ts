import { APP_INITIALIZER, ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './auth/auth.interceptor';
import { loadAppConfig } from './app-config';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // Runtime config first (Keycloak coordinates), then the interceptor that
    // signs every /api call with the session token.
    //
    // Keycloak init is intentionally NOT an APP_INITIALIZER here: the public
    // landing route renders without touching Keycloak, and the session is
    // established lazily on the first sign-in (see landing/signIn) or the first
    // guarded navigation / API call — both route through auth.service's
    // ensureAuthenticated()/accessToken(), unchanged.
    provideHttpClient(withInterceptors([authInterceptor])),
    {
      provide: APP_INITIALIZER,
      multi: true,
      deps: [HttpClient],
      useFactory: loadAppConfig,
    },
  ],
};

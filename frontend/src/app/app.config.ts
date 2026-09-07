import { APP_INITIALIZER, ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './auth/auth.interceptor';
import { loadAppConfig } from './app-config';
import { initSession } from './auth/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // Runtime config first (Keycloak coordinates), then one silent session bootstrap,
    // then the interceptor that signs every /api call with the session token.
    provideHttpClient(withInterceptors([authInterceptor])),
    {
      provide: APP_INITIALIZER,
      multi: true,
      deps: [HttpClient],
      useFactory: loadAppConfig,
    },
    {
      provide: APP_INITIALIZER,
      multi: true,
      useFactory: () => () => initSession(),
    },
  ],
};

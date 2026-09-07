import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { Observable, from, throwError } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { accessToken } from './auth.service';

/**
 * The single auth seam for every API call: attaches the Keycloak bearer token to
 * same-origin /api requests and turns an expired/invalid session into one clear,
 * actionable message instead of a bare 401. Components no longer build Authorization
 * headers by hand — they call the API and this interceptor signs the request.
 *
 * Asset requests (/assets/config.json) and non-/api traffic pass through untouched.
 */
export function authInterceptor(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> {
  if (!req.url.startsWith('/api')) {
    return next(req);
  }
  return from(accessToken()).pipe(
    switchMap((token) => {
      const signed =
        token != null ? req.clone({ setHeaders: { Authorization: 'Bearer ' + token } }) : req;
      return next(signed).pipe(
        catchError((err: unknown) => {
          if (err instanceof HttpErrorResponse && err.status === 401) {
            const expired = new HttpErrorResponse({
              error: err.error,
              headers: err.headers,
              status: err.status,
              statusText: 'Your session has expired. Please sign in again.',
              url: err.url ?? undefined,
            });
            return throwError(() => expired);
          }
          return throwError(() => err);
        }),
      );
    }),
  );
}

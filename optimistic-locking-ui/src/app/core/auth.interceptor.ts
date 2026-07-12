import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { DemoIdentityService } from './demo-identity.service';

/**
 * Attaches the demo bearer token to /lines/** calls. `X-Partner-Id` (ADR 0001) is added to every
 * method except DELETE: DELETE is the one business-partner-*independent* operation (line_#delete
 * is a user role, not scoped to a partner -- see LineAuthorization), so it's deliberately left off
 * here to mirror the backend's own model instead of sending a header the delete endpoint ignores.
 * GET, and PUT (create, and the four per-point move endpoints) are all business-partner-scoped
 * (line_#read / line_#create), so they all need it.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/lines')) {
    return next(req);
  }

  const token = inject(DemoIdentityService).token();
  if (!token) {
    return next(req);
  }

  const setHeaders: Record<string, string> = { Authorization: `Bearer ${token}` };
  if (req.method !== 'DELETE') {
    setHeaders['X-Partner-Id'] = 'acme';
  }
  return next(req.clone({ setHeaders }));
};

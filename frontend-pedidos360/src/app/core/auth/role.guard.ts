import { inject } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  Router
} from '@angular/router';

import { MsalService } from '@azure/msal-angular';
import { from, map, catchError, of } from 'rxjs';

import { environment } from '../../../environments/environment';

function decodeJwtPayload(token: string): any {
  const payload = token.split('.')[1];

  const normalized = payload
    .replace(/-/g, '+')
    .replace(/_/g, '/');

  const padded = normalized.padEnd(
    normalized.length + (4 - normalized.length % 4) % 4,
    '='
  );

  const decoded = atob(padded);

  return JSON.parse(
    decodeURIComponent(
      decoded
        .split('')
        .map(
          char =>
            '%' +
            ('00' + char.charCodeAt(0).toString(16)).slice(-2)
        )
        .join('')
    )
  );
}

export const roleGuard: CanActivateFn = (
  route: ActivatedRouteSnapshot
) => {

  const msalService = inject(MsalService);
  const router = inject(Router);

  let account = msalService.instance.getActiveAccount();

  if (!account) {
    const accounts = msalService.instance.getAllAccounts();

    if (accounts.length > 0) {
      account = accounts[0];
      msalService.instance.setActiveAccount(account);
    }
  }

  // Si realmente no existe sesión, MsalGuard se encargará.
  if (!account) {
    return true;
  }

  const allowedRoles =
    (route.data['roles'] as string[]) ?? [];

  return from(
    msalService.instance.acquireTokenSilent({
      account,
      scopes: [environment.msal.apiScope]
    })
  ).pipe(
    map(result => {

      const claims = decodeJwtPayload(result.accessToken);

      const userRoles: string[] = claims.roles ?? [];

      console.log('Roles requeridos:', allowedRoles);
      console.log('Roles usuario:', userRoles);

      const authorized = allowedRoles.some(
        role => userRoles.includes(role)
      );

      if (authorized) {
        console.log('RoleGuard: acceso permitido ✅');
        return true;
      }

      console.warn('RoleGuard: acceso denegado ❌');

      return router.createUrlTree(['/access-denied']);
    }),

    catchError(error => {
      console.error('Error comprobando roles:', error);
      return of(router.createUrlTree(['/access-denied']));
    })
  );
};
import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { MsalService } from '@azure/msal-angular';

export const authGuard: CanActivateFn = () => {
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

  if (account) {
    return true;
  }

  return router.createUrlTree(['/login']);
};

import { Routes } from '@angular/router';
import { MsalGuard } from '@azure/msal-angular';

import { Login } from './pages/login/login';
import { AuthCallback } from './pages/auth-callback/auth-callback';
import { Dashboard } from './pages/dashboard/dashboard';
import { AccessDenied } from './pages/access-denied/access-denied';
import { Catalog } from './pages/catalog/catalog';


export const routes: Routes = [
  {
    path: 'login',
    component: Login
  },
  {
    path: 'auth/callback',
    component: AuthCallback
  },
  {
    path: 'dashboard',
    component: Dashboard,
    canActivate: [MsalGuard]
  },
  {
    path: 'catalog',
    component: Catalog,
    canActivate: [MsalGuard]
  },
  {
    path: 'access-denied',
    component: AccessDenied
  },
  {
    path: '',
    redirectTo: 'dashboard',
    pathMatch: 'full'
  },
  {
    path: '**',
    redirectTo: 'dashboard'
  }
];
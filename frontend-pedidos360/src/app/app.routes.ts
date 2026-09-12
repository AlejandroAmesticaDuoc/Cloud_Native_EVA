import { Routes } from '@angular/router';
import { Login } from './pages/login/login';
import { AuthCallback } from './pages/auth-callback/auth-callback';

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
    path: '',
    redirectTo: 'login',
    pathMatch: 'full'
  },
  {
    path: '**',
    redirectTo: 'login'
  }
];
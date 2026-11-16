import { Routes } from '@angular/router';

import { Login } from './pages/login/login';
import { AuthCallback } from './pages/auth-callback/auth-callback';
import { Dashboard } from './pages/dashboard/dashboard';
import { AccessDenied } from './pages/access-denied/access-denied';
import { Catalog } from './pages/catalog/catalog';
import { Orders } from './pages/orders/orders';
import { Reports } from './pages/reports/reports';
import { Audit } from './pages/audit/audit';
import { KafkaAdmin } from './pages/kafka-admin/kafka-admin';
import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';

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
    canActivate: [authGuard]
  },
  {
    path: 'catalog',
    component: Catalog,
    canActivate: [authGuard]
  },
  {
    path: 'orders',
    component: Orders,
    canActivate: [authGuard]
  },
  {
    path: 'reports',
    component: Reports,
    canActivate: [authGuard]
  },
  {
    path: 'audit',
    component: Audit,
    canActivate: [authGuard]
  },
  {
    path: 'kafka-admin',
    component: KafkaAdmin,
    canActivate: [authGuard, roleGuard],
    data: {
      roles: ['ADMIN', 'OPERADOR']
    }
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

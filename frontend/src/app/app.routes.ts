import { Routes } from '@angular/router';
import { claimantGuard, internalGuard, supervisorGuard } from './auth/auth.guard';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./home/home').then((m) => m.Home) },
  {
    path: 'claim/new',
    canActivate: [claimantGuard],
    loadComponent: () => import('./fnol/fnol').then((m) => m.Fnol),
  },
  {
    path: 'claims',
    canActivate: [claimantGuard],
    loadComponent: () => import('./my-claims/my-claims').then((m) => m.MyClaims),
  },
  {
    path: 'policies',
    canActivate: [claimantGuard],
    loadComponent: () => import('./policies-cockpit/policies-cockpit').then((m) => m.PoliciesCockpit),
  },
  {
    path: 'policies/:policyNumber',
    canActivate: [claimantGuard],
    loadComponent: () => import('./policies-cockpit/policy-detail').then((m) => m.PolicyDetail),
  },
  {
    path: 'claim/:claimNumber',
    canActivate: [claimantGuard],
    loadComponent: () => import('./claim-status/claim-status').then((m) => m.ClaimStatus),
  },
  {
    path: 'queue',
    canActivate: [internalGuard],
    loadComponent: () => import('./queue/queue').then((m) => m.Queue),
  },
  {
    path: 'overview',
    canActivate: [supervisorGuard],
    loadComponent: () => import('./overview/overview').then((m) => m.Overview),
  },
  {
    path: 'escalations',
    canActivate: [supervisorGuard],
    loadComponent: () => import('./escalations/escalations').then((m) => m.Escalations),
  },
  {
    path: 'admin/authority',
    canActivate: [supervisorGuard],
    loadComponent: () => import('./authority/authority').then((m) => m.Authority),
  },
  {
    path: 'admin/policies',
    canActivate: [supervisorGuard],
    loadComponent: () => import('./policies/policies').then((m) => m.Policies),
  },
  {
    path: 'admin/staff',
    canActivate: [supervisorGuard],
    loadComponent: () => import('./staff/staff').then((m) => m.Staff),
  },
  {
    path: 'admin/privacy',
    canActivate: [supervisorGuard],
    loadComponent: () => import('./privacy/privacy').then((m) => m.Privacy),
  },
  {
    path: 'claims/:claimNumber',
    canActivate: [internalGuard],
    loadComponent: () => import('./claim-detail/claim-detail').then((m) => m.ClaimDetail),
  },
  { path: '**', redirectTo: '' },
];

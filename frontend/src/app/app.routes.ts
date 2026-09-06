import { Routes } from '@angular/router';
import { claimantGuard, internalGuard } from './auth/auth.guard';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./home/home').then((m) => m.Home) },
  {
    path: 'claim/new',
    canActivate: [claimantGuard],
    loadComponent: () => import('./fnol/fnol').then((m) => m.Fnol),
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
    path: 'claims/:claimNumber',
    canActivate: [internalGuard],
    loadComponent: () => import('./claim-detail/claim-detail').then((m) => m.ClaimDetail),
  },
  { path: '**', redirectTo: '' },
];

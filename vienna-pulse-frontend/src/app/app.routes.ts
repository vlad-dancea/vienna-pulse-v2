import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./map-view/map-view').then((module) => module.MapView),
  },
  // Cloudflare serves the app for every path, so an unknown one shows the map instead of nothing.
  { path: '**', redirectTo: '' },
];

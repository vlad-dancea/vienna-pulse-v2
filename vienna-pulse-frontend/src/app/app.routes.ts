import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./map-view/map-view').then((module) => module.MapView),
  },
];

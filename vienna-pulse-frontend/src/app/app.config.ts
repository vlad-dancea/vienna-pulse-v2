import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { apiOriginInterceptor } from './api-origin';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Route and query parameters reach the routed component as inputs, such as the map's open stop.
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([apiOriginInterceptor])),
  ],
};

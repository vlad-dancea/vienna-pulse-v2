import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_ORIGIN, apiOriginInterceptor } from './api-origin';

describe('apiOriginInterceptor', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiOriginInterceptor])),
        provideHttpClientTesting(),
        { provide: API_ORIGIN, useValue: 'https://pulse-api.example.org' },
      ],
    });
  });

  it('sends API requests to the API origin and leaves other requests on the page origin', () => {
    const http = TestBed.inject(HttpClient);
    const controller = TestBed.inject(HttpTestingController);
    for (const url of ['/api/lines?date=2026-10-23', '/data/vienna-districts.geojson', '/apiary']) {
      http.get(url).subscribe();
    }
    controller.expectOne('https://pulse-api.example.org/api/lines?date=2026-10-23').flush({});
    controller.expectOne('/data/vienna-districts.geojson').flush({});
    controller.expectOne('/apiary').flush({});
    controller.verify();
  });
});

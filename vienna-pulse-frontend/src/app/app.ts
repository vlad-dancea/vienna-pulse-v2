import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Hosts the Vienna Pulse views through the router. */
@Component({
  imports: [RouterOutlet],
  selector: 'app-root',
  templateUrl: './app.html',
})
export class App {}

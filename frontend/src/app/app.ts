import { Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { hasRole } from './auth/auth.service';

@Component({
  imports: [RouterOutlet, RouterLink],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  /** Supervisor-only nav links (the routes are guarded too — slice 5, slice 7). */
  protected isSupervisor(): boolean {
    return hasRole('supervisor');
  }
}

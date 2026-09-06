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
  /** The escalation queue link is shown to supervisors only (the route is guarded too). */
  protected isSupervisor(): boolean {
    return hasRole('supervisor');
  }
}

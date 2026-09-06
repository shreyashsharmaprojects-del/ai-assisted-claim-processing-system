import { Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { hasRole, isAuthenticated, logout } from './auth/auth.service';

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

  /** The adjuster queue link is internal-only (never advertised to the public). */
  protected isInternal(): boolean {
    return hasRole('adjuster_l1') || hasRole('adjuster_l2') || hasRole('supervisor');
  }

  protected isSignedIn(): boolean {
    return isAuthenticated();
  }

  protected async signOut(): Promise<void> {
    await logout();
  }
}

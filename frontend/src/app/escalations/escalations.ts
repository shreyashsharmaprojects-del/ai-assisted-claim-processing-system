import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { accessToken } from '../auth/auth.service';

interface QueueClaimView {
  claimNumber: string;
  status: string;
  level: string;
  policyNumber: string;
  lossDate: string;
  lossLocation: string;
  lossDescription: string;
  createdAt: string;
  assignedTo: string | null;
}

/** The supervisor's escalation queue (route-table row for /escalations, slice 5). */
@Component({
  imports: [RouterLink],
  selector: 'app-escalations',
  styleUrl: './escalations.css',
  templateUrl: './escalations.html',
})
export class Escalations {
  private readonly http = inject(HttpClient);

  protected readonly claims = signal<QueueClaimView[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);

  constructor() {
    void this.load();
  }

  async load() {
    this.error.set(null);
    try {
      const token = await accessToken();
      if (!token) {
        this.error.set('You are not signed in.');
        return;
      }
      const headers = new HttpHeaders().set('Authorization', 'Bearer ' + token);
      const rows = await firstValueFrom(
        this.http.get<QueueClaimView[]>('/api/escalations', { headers }),
      );
      this.claims.set(rows);
    } catch {
      this.error.set('Could not load the escalation queue. Please try again.');
    } finally {
      this.loaded.set(true);
    }
  }
}

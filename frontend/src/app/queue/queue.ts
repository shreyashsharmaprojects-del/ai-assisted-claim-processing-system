import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { accessToken } from '../auth/auth.service';

export interface QueueClaimView {
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

@Component({
  imports: [RouterLink],
  selector: 'app-queue',
  styleUrl: './queue.css',
  templateUrl: './queue.html',
})
export class Queue {
  private readonly http = inject(HttpClient);

  protected readonly claims = signal<QueueClaimView[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);

  constructor() {
    void this.load();
  }

  async load() {
    this.error.set(null);
    this.loaded.set(false);
    try {
      const token = await accessToken();
      if (!token) {
        this.error.set('You are not signed in.');
        return;
      }
      const headers = new HttpHeaders().set('Authorization', 'Bearer ' + token);
      const rows = await firstValueFrom(this.http.get<QueueClaimView[]>('/api/queue', { headers }));
      this.claims.set(rows);
    } catch {
      this.error.set('Could not load your queue. Please try again.');
    } finally {
      this.loaded.set(true);
    }
  }
}

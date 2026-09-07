import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { serverMessage } from '../toasts';

export interface PolicySummary {
  policyNumber: string;
  productCode: string;
  holderName: string;
}

@Component({
  imports: [RouterLink],
  selector: 'app-home',
  styleUrl: './home.css',
  templateUrl: './home.html',
})
export class Home {
  private readonly http = inject(HttpClient);

  protected readonly policies = signal<PolicySummary[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly forbidden = signal(false);

  constructor() {
    void this.load();
  }

  load() {
    this.error.set(null);
    this.forbidden.set(false);
    this.loaded.set(false);
    this.http.get<PolicySummary[]>('/api/policies').subscribe({
      next: (policies) => {
        this.policies.set(policies);
        this.loaded.set(true);
      },
      error: (err: unknown) => {
        const status =
          typeof err === 'object' && err !== null && 'status' in err
            ? (err as { status: unknown }).status
            : null;
        if (status === 401) {
          // Anonymous visitor: the reference list needs a session. Keep the page
          // useful — the primary CTAs (file/track) trigger sign-in themselves.
          this.forbidden.set(true);
        } else {
          this.error.set(serverMessage(err, 'Could not load policies from the backend.'));
        }
        this.loaded.set(true);
      },
    });
  }
}

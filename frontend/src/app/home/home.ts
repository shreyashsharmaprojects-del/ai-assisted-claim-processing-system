import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface PolicySummary {
  policyNumber: string;
  productCode: string;
  holderName: string;
}

@Component({
  selector: 'app-home',
  styleUrl: './home.css',
  templateUrl: './home.html',
})
export class Home {
  private readonly http = inject(HttpClient);

  protected readonly policies = signal<PolicySummary[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);

  constructor() {
    this.load();
  }

  load() {
    this.error.set(null);
    this.loaded.set(false);
    this.http.get<PolicySummary[]>('/api/policies').subscribe({
      next: (policies) => {
        this.policies.set(policies);
        this.loaded.set(true);
      },
      error: () => {
        this.error.set('Could not load policies from the backend.');
        this.loaded.set(true);
      },
    });
  }
}

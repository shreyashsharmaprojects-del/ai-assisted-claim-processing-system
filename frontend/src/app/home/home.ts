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

  constructor() {
    this.http.get<PolicySummary[]>('/api/policies').subscribe({
      next: (policies) => this.policies.set(policies),
      error: () => this.error.set('Could not load policies from the backend.'),
    });
  }
}

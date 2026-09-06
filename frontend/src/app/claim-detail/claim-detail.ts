import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { accessToken } from '../auth/auth.service';

interface AttachmentView {
  id: number;
  originalName: string;
}

interface NoteView {
  id: number;
  body: string;
  author: string | null;
}

interface InternalClaimView {
  claimNumber: string;
  status: string;
  level: string;
  policyNumber: string;
  productCode: string;
  coverage: unknown;
  holderName: string;
  lossDate: string;
  lossLocation: string;
  lossDescription: string;
  claimantRemarks: string | null;
  reserveAmount: number | null;
  assignedTo: string | null;
  attachments: AttachmentView[];
  notes: NoteView[];
}

/** The adjuster's claim screen (journey 4): full internal view, reserve, notes, photos. */
@Component({
  imports: [FormsModule, RouterLink],
  selector: 'app-claim-detail',
  styleUrl: './claim-detail.css',
  templateUrl: './claim-detail.html',
})
export class ClaimDetail {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);

  protected readonly claimNumber = this.route.snapshot.paramMap.get('claimNumber') ?? '';
  protected readonly view = signal<InternalClaimView | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loaded = signal(false);

  protected reserveInput = '';
  protected noteInput = '';

  constructor() {
    void this.load();
  }

  coverageText(): string {
    const coverage = this.view()?.coverage;
    return coverage == null ? '' : JSON.stringify(coverage, null, 2);
  }

  async load() {
    this.error.set(null);
    try {
      const headers = await this.authHeaders();
      if (!headers) {
        return;
      }
      const view = await firstValueFrom(
        this.http.get<InternalClaimView>(`/api/claims/${this.claimNumber}/full`, { headers }),
      );
      this.view.set(view);
      this.reserveInput = view.reserveAmount == null ? '' : String(view.reserveAmount);
    } catch {
      this.error.set('Could not load this claim.');
    } finally {
      this.loaded.set(true);
    }
  }

  async saveReserve() {
    this.error.set(null);
    const headers = await this.authHeaders();
    if (!headers) {
      return;
    }
    const amount = Number(this.reserveInput);
    try {
      const view = await firstValueFrom(
        this.http.put<InternalClaimView>(
          `/api/claims/${this.claimNumber}/reserve`,
          { amount },
          { headers },
        ),
      );
      this.view.set(view);
    } catch {
      this.error.set('Could not save the reserve.');
    }
  }

  async addNote() {
    this.error.set(null);
    const headers = await this.authHeaders();
    if (!headers) {
      return;
    }
    try {
      await firstValueFrom(
        this.http.post<NoteView>(
          `/api/claims/${this.claimNumber}/notes`,
          { body: this.noteInput.trim() },
          { headers },
        ),
      );
      this.noteInput = '';
      await this.load();
    } catch {
      this.error.set('Could not add the note.');
    }
  }

  async download(attachment: AttachmentView) {
    const headers = await this.authHeaders();
    if (!headers) {
      return;
    }
    try {
      const blob = await firstValueFrom(
        this.http.get(`/api/claims/${this.claimNumber}/attachments/${attachment.id}`, {
          headers,
          responseType: 'blob',
        }),
      );
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = attachment.originalName;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch {
      this.error.set('Could not download the photo.');
    }
  }

  private async authHeaders(): Promise<HttpHeaders | null> {
    const token = await accessToken();
    if (!token) {
      this.error.set('You are not signed in.');
      return null;
    }
    return new HttpHeaders().set('Authorization', 'Bearer ' + token);
  }
}

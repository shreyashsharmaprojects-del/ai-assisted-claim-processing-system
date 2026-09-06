import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { accessToken } from '../auth/auth.service';

interface ClaimantClaimView {
  claimNumber: string;
  status: string;
  steps: string[];
}

@Component({
  imports: [FormsModule],
  selector: 'app-fnol',
  styleUrl: './fnol.css',
  templateUrl: './fnol.html',
})
export class Fnol {
  private readonly http = inject(HttpClient);

  protected policyNumber = '';
  protected holderName = '';
  protected holderEmail = '';
  protected lossDate = '';
  protected lossLocation = '';
  protected lossDescription = '';
  protected remarks = '';
  protected photoFiles: FileList | null = null;

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<ClaimantClaimView | null>(null);

  onPhotosSelected(event: Event) {
    this.photoFiles = (event.target as HTMLInputElement).files;
  }

  async submit() {
    this.error.set(null);
    this.result.set(null);
    this.submitting.set(true);
    try {
      const token = await accessToken();
      if (!token) {
        this.error.set('You are not signed in.');
        return;
      }
      const form = new FormData();
      form.append('policyNumber', this.policyNumber.trim());
      form.append('holderName', this.holderName.trim());
      form.append('holderEmail', this.holderEmail.trim());
      form.append('lossDate', this.lossDate);
      form.append('lossLocation', this.lossLocation.trim());
      form.append('lossDescription', this.lossDescription.trim());
      if (this.remarks.trim()) {
        form.append('remarks', this.remarks.trim());
      }
      if (this.photoFiles) {
        for (const file of Array.from(this.photoFiles)) {
          form.append('photos', file);
        }
      }
      const headers = new HttpHeaders().set('Authorization', 'Bearer ' + token);
      const view = await firstValueFrom(
        this.http.post<ClaimantClaimView>('/api/claims', form, { headers }),
      );
      this.result.set(view);
    } catch (err) {
      const body = (err as { error?: { message?: string } })?.error?.message;
      this.error.set(body ?? 'Something went wrong. Please try again.');
    } finally {
      this.submitting.set(false);
    }
  }
}

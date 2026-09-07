import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { serverMessage } from '../toasts';

interface ClaimantClaimView {
  claimNumber: string;
  status: string;
  steps: string[];
}

const MAX_PHOTOS = 5;
const MAX_PHOTO_MB = 10;

@Component({
  imports: [FormsModule, RouterLink],
  selector: 'app-fnol',
  styleUrl: './fnol.css',
  templateUrl: './fnol.html',
})
export class Fnol {
  private readonly http = inject(HttpClient);

  protected step: 1 | 2 = 1;
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

  // --- step 1 (policy) ------------------------------------------------------

  protected step1Valid(): boolean {
    return (
      this.policyNumber.trim() !== '' &&
      this.holderName.trim() !== '' &&
      /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.holderEmail.trim())
    );
  }

  protected step1Hint(): string {
    if (this.policyNumber.trim() === '' || this.holderName.trim() === '') {
      return 'Enter the policy number and holder name exactly as they appear on the policy document.';
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.holderEmail.trim())) {
      return 'Enter a valid policyholder email — the claim number and updates go there.';
    }
    return '';
  }

  protected nextStep(): void {
    this.error.set(null);
    if (this.step1Valid()) {
      this.step = 2;
    }
  }

  protected prevStep(): void {
    this.error.set(null);
    this.step = 1;
  }

  // --- step 2 (loss) --------------------------------------------------------

  /** Client-side photo guard: count + per-file size/type, before the server round-trip. */
  protected photoHint(): string {
    if (!this.photoFiles || this.photoFiles.length === 0) {
      return 'Photos speed up your claim; you can add up to 5.';
    }
    const names: string[] = [];
    for (const file of Array.from(this.photoFiles)) {
      if (!file.type.startsWith('image/')) {
        return `"${file.name}" is not an image — only image files are accepted.`;
      }
      if (file.size > MAX_PHOTO_MB * 1024 * 1024) {
        return `"${file.name}" is over ${MAX_PHOTO_MB} MB — choose a smaller photo.`;
      }
      names.push(file.name);
    }
    if (this.photoFiles.length > MAX_PHOTOS) {
      return `At most ${MAX_PHOTOS} photos may be attached — you selected ${this.photoFiles.length}.`;
    }
    return `${this.photoFiles.length} photo${this.photoFiles.length === 1 ? '' : 's'} selected: ${names.join(', ')}.`;
  }

  protected step2Valid(): boolean {
    if (this.lossDate === '' || this.lossLocation.trim() === '' || this.lossDescription.trim() === '') {
      return false;
    }
    if (this.photoFiles && this.photoFiles.length > 0) {
      if (this.photoFiles.length > MAX_PHOTOS) {
        return false;
      }
      for (const file of Array.from(this.photoFiles)) {
        if (!file.type.startsWith('image/') || file.size > MAX_PHOTO_MB * 1024 * 1024) {
          return false;
        }
      }
    }
    return true;
  }

  onPhotosSelected(event: Event) {
    this.photoFiles = (event.target as HTMLInputElement).files;
  }

  async submit() {
    this.error.set(null);
    this.result.set(null);
    if (!this.step1Valid() || !this.step2Valid()) {
      this.error.set('Check the highlighted details before submitting.');
      return;
    }
    this.submitting.set(true);
    try {
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
      const view = await firstValueFrom(this.http.post<ClaimantClaimView>('/api/claims', form));
      this.result.set(view);
    } catch (err) {
      if (err instanceof HttpErrorResponse && err.status === 429) {
        this.error.set(
          serverMessage(err, 'Too many claims filed recently. Please wait before filing another.'),
        );
      } else {
        this.error.set(serverMessage(err, 'Something went wrong. Please try again.'));
      }
    } finally {
      this.submitting.set(false);
    }
  }
}

import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { badgeClass } from '../ui';
import { Toasts, serverMessage } from '../toasts';

export interface StaffRow {
  id: number;
  displayName: string;
  email: string;
  level: string;
  active: boolean;
  openClaims: number;
  keycloakSub: string | null;
}

/**
 * Supervisor staff surface (S7): who exists, who's loaded, and the active
 * toggle. Deactivation drains the adjuster's open queue server-side; the
 * confirm states the affected-claim count before anything is sent. No
 * Keycloak writes — the subject is shown with a copy affordance so the
 * supervisor can paste it into the console.
 */
@Component({
  imports: [RouterLink],
  selector: 'app-staff',
  templateUrl: './staff.html',
})
export class Staff {
  private readonly http = inject(HttpClient);
  private readonly toasts = inject(Toasts);

  protected readonly rows = signal<StaffRow[]>([]);
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly pending = signal<StaffRow | null>(null);
  protected readonly toggling = signal(false);

  constructor() {
    void this.load();
  }

  protected activeBadge(active: boolean): string {
    return badgeClass(active ? 'ACTIVE' : 'RETIRED');
  }

  protected activeCount(): number {
    return this.rows().filter((row) => row.active).length;
  }

  async load() {
    this.error.set(null);
    this.loaded.set(false);
    try {
      const rows = await firstValueFrom(this.http.get<StaffRow[]>('/api/staff'));
      this.rows.set(rows);
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not load the staff list. Please try again.'));
    } finally {
      this.loaded.set(true);
    }
  }

  protected askToggle(row: StaffRow): void {
    this.pending.set(row);
  }

  protected cancelToggle(): void {
    this.pending.set(null);
  }

  /** Confirm calls PUT /api/staff/{id}/active {active} then reloads the list. */
  async confirmToggle() {
    const target = this.pending();
    if (!target || this.toggling()) {
      return;
    }
    this.toggling.set(true);
    try {
      await firstValueFrom(
        this.http.put(`/api/staff/${target.id}/active`, { active: !target.active }),
      );
      this.pending.set(null);
      this.toasts.success(
        target.active
          ? `${target.displayName} deactivated. Open claims moved to other adjusters.`
          : `${target.displayName} reactivated.`,
      );
      await this.load();
    } catch (err) {
      this.error.set(serverMessage(err, 'Could not update this person. Please try again.'));
    } finally {
      this.toggling.set(false);
    }
  }

  /** Copy the Keycloak subject for console provisioning (no Keycloak writes here). */
  protected async copySub(row: StaffRow): Promise<void> {
    if (!row.keycloakSub) {
      return;
    }
    try {
      await navigator.clipboard.writeText(row.keycloakSub);
    } catch {
      const area = document.createElement('textarea');
      area.value = row.keycloakSub;
      document.body.appendChild(area);
      area.select();
      document.execCommand('copy');
      area.remove();
    }
    this.toasts.info('Keycloak subject copied.');
  }
}

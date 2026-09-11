import { Component, EventEmitter, Input, Output } from '@angular/core';
import { PolicyDetail } from '../policies-cockpit/policy-detail';

/**
 * Whole policy page in a modal (owner call): the claim workspace opens the
 * existing policy-detail surface in place instead of navigating. Thin shell —
 * scrim, 880px complex dialog, Close — around the shared component. Esc /
 * scrim / Close emit `closed`. The parent owns open state + focus discipline.
 */
@Component({
  imports: [PolicyDetail],
  selector: 'app-policy-modal',
  styleUrl: './policy-modal.css',
  templateUrl: './policy-modal.html',
})
export class PolicyModalComponent {
  @Input() policyNumber = '';
  @Output() readonly closed = new EventEmitter<void>();

  protected close(): void {
    this.closed.emit();
  }
}

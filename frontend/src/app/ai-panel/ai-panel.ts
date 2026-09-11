import { Component, Input } from '@angular/core';
import { AiChatComponent } from '../ai-chat/ai-chat';

/**
 * AI section for the claim workspace: the conversational thread only.
 * Owner call — the per-cover advisory artifact (rules-only fallback cards,
 * refresh button) was removed from the frontend; the chatbot is the single
 * AI surface. Thin wrapper so the drawer mount and `detail-ai` testid stay
 * stable. Advisory only — the adjuster decides (disclaimer lives in the chat).
 */
@Component({
  imports: [AiChatComponent],
  selector: 'app-ai-panel',
  styleUrl: './ai-panel.css',
  templateUrl: './ai-panel.html',
})
export class AiPanelComponent {
  @Input() claimNumber = '';
}

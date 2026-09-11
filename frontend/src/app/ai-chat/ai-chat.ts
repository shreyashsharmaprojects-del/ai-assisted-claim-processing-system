import { AfterViewChecked, Component, ElementRef, Input, OnChanges, SimpleChanges, ViewChild, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { serverMessage } from '../toasts';

/** One turn in the claim Q&A thread (POST /api/claims/{claimNumber}/ai-chat). */
export interface AiChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

/** Full-transcript reply: the server replays history plus the new Q+A. */
interface AiChatReply {
  messages: AiChatMessage[];
  status: 'COMPLETED' | 'DEGRADED';
  model: string;
  errorMessage: string | null;
}

/**
 * Conversational AI thread for one claim. Renders above the per-cover advisory
 * artifact at every stage: the adjuster asks free-form questions, the server
 * replays the transcript plus the new answer, and a transport failure keeps
 * the pushed question with a retry hint instead of losing it. The transcript
 * is capped at the last 20 turns (mirroring the server's history cap, so a
 * long session can never 400 and always recovers).
 */
@Component({
  selector: 'app-ai-chat',
  imports: [FormsModule],
  styleUrl: './ai-chat.css',
  templateUrl: './ai-chat.html',
})
export class AiChatComponent implements OnChanges, AfterViewChecked {
  private readonly http = inject(HttpClient);

  /** Maximum rendered turns; the full transcript is still sent as history. */
  private static readonly MAX_RENDERED = 50;
  /**
   * Transcript cap: the server rejects histories longer than 20 entries
   * (400), so the client never holds more — every send recovers instead of
   * failing forever.
   */
  private static readonly MAX_HISTORY = 20;
  /** Maximum question length, mirroring the server's 2000-char cap. */
  private static readonly MAX_QUESTION = 2000;
  private static readonly OFFLINE_REPLY = 'Could not reach the assistant. Try again.';

  @Input() claimNumber = '';

  protected readonly messages = signal<AiChatMessage[]>([]);
  protected readonly draft = signal('');
  protected readonly sending = signal(false);
  protected readonly chatError = signal<string | null>(null);
  /** "Live" after a COMPLETED turn, "Fallback" after a rules-only turn, else null. */
  protected readonly chatStatus = signal<string | null>(null);
  /** Scroll container for the transcript (auto-scrolls to the latest turn). */
  @ViewChild('thread') private readonly thread?: ElementRef<HTMLElement>;
  /** True once a fresh turn rendered since the last scroll pass. */
  private pendingScroll = false;

  ngAfterViewChecked(): void {
    if (!this.pendingScroll) {
      return;
    }
    this.pendingScroll = false;
    const el = this.thread?.nativeElement;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['claimNumber'] && !changes['claimNumber'].isFirstChange()) {
      this.messages.set([]);
      this.chatError.set(null);
      this.chatStatus.set(null);
    }
  }

  /** Last 50 turns for rendering; indices feed the detail-ai-msg-{index} testids. */
  protected visibleMessages(): AiChatMessage[] {
    const all = this.messages();
    if (all.length <= AiChatComponent.MAX_RENDERED) {
      return all;
    }
    return all.slice(all.length - AiChatComponent.MAX_RENDERED);
  }

  /** Enter sends (Shift+Enter keeps the newline; ⌘/Ctrl+Enter also sends). */
  protected onEnter(event: Event): void {
    const key = event as KeyboardEvent;
    if (key.shiftKey && !key.metaKey && !key.ctrlKey) {
      return;
    }
    key.preventDefault();
    void this.send();
  }

  /** Retry the last question after a transport failure (transcript is kept). */
  protected retry(): void {
    const turns = this.messages();
    const lastUser = [...turns].reverse().find((m) => m.role === 'user');
    if (!lastUser || this.sending()) {
      return;
    }
    this.draft.set(lastUser.content);
    void this.send();
  }

  protected async send(): Promise<void> {
    const question = (this.draft() ?? '').trim().slice(0, AiChatComponent.MAX_QUESTION);
    if (question === '' || this.sending()) {
      return;
    }
    // History is the transcript BEFORE this question, capped at the last 20
    // turns (the server rejects longer histories with 400); the server
    // replays it with the new Q+A appended.
    const history = this.messages().slice(-AiChatComponent.MAX_HISTORY);
    this.messages.set([...history, { role: 'user', content: question }]);
    this.pendingScroll = true;
    this.draft.set('');
    this.chatError.set(null);
    const claimNumber = (this.claimNumber ?? '').trim();
    if (claimNumber === '') {
      this.messages.update((all) => [...all, { role: 'assistant', content: AiChatComponent.OFFLINE_REPLY }]);
      this.pendingScroll = true;
      return;
    }
    this.sending.set(true);
    try {
      // Auth rides the shared authInterceptor (Bearer token on /api) — no
      // per-component Authorization header is built here.
      const reply = await firstValueFrom(
        this.http.post<AiChatReply>(`/api/claims/${encodeURIComponent(claimNumber)}/ai-chat`, {
          messages: history,
          question,
        }),
      );
      this.messages.set(reply.messages ?? []);
      this.pendingScroll = true;
      this.chatStatus.set(reply.status === 'COMPLETED' ? 'Live' : 'Fallback');
    } catch (err) {
      // Transport failure: the pushed question stays; the assistant turn
      // carries the retry hint so nothing the adjuster typed is lost.
      this.messages.update((all) => [...all, { role: 'assistant', content: AiChatComponent.OFFLINE_REPLY }]);
      this.pendingScroll = true;
      this.chatError.set(serverMessage(err, AiChatComponent.OFFLINE_REPLY));
    } finally {
      this.sending.set(false);
    }
  }
}

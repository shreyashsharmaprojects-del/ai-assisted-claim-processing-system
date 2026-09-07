import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';

export interface Toast {
  id: number;
  kind: 'success' | 'error' | 'info';
  message: string;
}

let nextId = 1;

/**
 * Quiet, specific notifications: success/error/info toasts with no confetti and no
 * shouting. Components report what happened ("Reserve saved", never "Awesome!"); errors
 * carry the request reference when the server sent one, so the toast doubles as the
 * support ticket seed. Auto-dismisses after 6s; errors stay until dismissed.
 */
@Injectable({ providedIn: 'root' })
export class Toasts {
  readonly items = signal<Toast[]>([]);

  show(kind: Toast['kind'], message: string): void {
    const toast: Toast = { id: nextId++, kind, message };
    this.items.update((all) => [...all, toast]);
    if (kind !== 'error') {
      setTimeout(() => this.dismiss(toast.id), 6000);
    }
  }

  success(message: string): void {
    this.show('success', message);
  }

  info(message: string): void {
    this.show('info', message);
  }

  error(message: string, err?: unknown): void {
    this.show('error', withReference(message, err));
  }

  dismiss(id: number): void {
    this.items.update((all) => all.filter((toast) => toast.id !== id));
  }
}

/** Appends "(Reference: xxxx)" when the failure carried a server request reference. */
export function withReference(message: string, err?: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as { message?: string } | string | null;
    const serverMessage = typeof body === 'string' ? body : body?.message;
    const match = /\(Reference: ([A-Za-z0-9_-]+)\)/.exec(serverMessage ?? '');
    if (match) {
      return `${message} (Reference: ${match[1]})`;
    }
  }
  return message;
}

/** The human message for an API failure: the server's actionable text, else a fallback. */
export function serverMessage(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) {
      return 'The server is unreachable. Check your connection and try again.';
    }
    // A dead proxy/backend answers with an HTML error page (502/504 "Bad Gateway") that
    // carries no JSON message — never show that raw text to a user.
    if (err.error == null || typeof err.error === 'string') {
      const text = typeof err.error === 'string' ? err.error.trim() : '';
      if (text === '' || text.startsWith('<')) {
        return fallback;
      }
      // A plain-text proxy error ("Bad Gateway", "Service Unavailable") is infrastructure
      // noise, not an actionable message — fall back to the screen's own wording.
      if (text.length < 80 && !/[.!?]$/.test(text)) {
        return fallback;
      }
      return text;
    }
    if (err.status === 429) {
      const body = err.error as { message?: string } | null;
      return body?.message ?? 'Too many requests. Please wait and try again.';
    }
    const body = err.error as { message?: string } | string | null;
    const text = typeof body === 'string' ? body : body?.message;
    if (text) {
      return text;
    }
    if (err.statusText) {
      return err.statusText;
    }
  }
  return fallback;
}

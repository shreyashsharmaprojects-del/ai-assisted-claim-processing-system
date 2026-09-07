/**
 * R4 shared pagination contract.
 *
 * New backend shape (all five lists): `{content, page, size, totalElements,
 * totalPages}` with `page`/`size`/`q`/`status` query params.
 * Old backend shape: a plain array (query params ignored).
 *
 * `normalizePage()` folds both into one `Page<T>` in exactly one place — every
 * list component renders the normalized page and never branches on the wire shape.
 * Components additionally track whether the server paged (`isEnvelope`) so the
 * client-side filter fallback only runs against the old backend.
 */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** True when the backend spoke the new paginated envelope (not a plain array). */
export function isEnvelope<T>(body: T[] | Page<T>): body is Page<T> {
  return !Array.isArray(body);
}

/** Fold an envelope or a legacy plain array into one `Page<T>`. Never throws. */
export function normalizePage<T>(
  body: T[] | Page<T> | null | undefined,
  fallbackSize = 25,
): Page<T> {
  if (Array.isArray(body)) {
    return {
      content: body,
      page: 0,
      size: body.length > 0 ? body.length : fallbackSize,
      totalElements: body.length,
      totalPages: 1,
    };
  }
  const content = body?.content ?? [];
  const totalElements = body?.totalElements ?? content.length;
  const size = body?.size != null && body.size > 0 ? body.size : fallbackSize;
  const totalPages = body?.totalPages ?? Math.max(1, Math.ceil(totalElements / size));
  return { content, page: body?.page ?? 0, size, totalElements, totalPages };
}

/**
 * Query params for a list fetch. `q` is trimmed and omitted when blank; `status`
 * is omitted for `ALL`/empty so unfiltered loads stay identical to the old calls.
 */
export function pageParams(
  page: number,
  size: number,
  q?: string,
  status?: string,
): { [key: string]: string } {
  const params: { [key: string]: string } = { page: String(page), size: String(size) };
  const needle = (q ?? '').trim();
  if (needle !== '') {
    params['q'] = needle;
  }
  if (status !== undefined && status !== '' && status !== 'ALL') {
    params['status'] = status;
  }
  return params;
}

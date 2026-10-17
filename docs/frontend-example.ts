// React 19 / TypeScript integration example. No tokens in localStorage or URLs.
export type Page<T> = { items: T[]; total: number; page: number; pageSize: number };
export type AuthUser = { id: string; name: string };
export type Category = 'CONCERT' | 'MUSICAL' | 'THEATER' | 'EXHIBITION' | 'CLASSICAL';
export type EventDto = {
  id: number; title: string; description: string; category: Category;
  venue: string | null; date: string | null; price: number | null;
  posterUrl: string | null; status: 'ON_SALE' | 'UPCOMING' | 'CLOSED';
};
export type QueueDto = {
  state: 'WAITING' | 'ADMITTED' | 'EXPIRED' | 'CANCELLED';
  ticketNumber: number; aheadCount: number; estimatedWaitSeconds: number | null;
  admissionToken: string | null; admissionExpiresAt: string | null; pollAfterSeconds: number;
};
export type SeatDto = {
  id: string; row: string; number: number; x: number; y: number;
  grade: 'VIP' | 'R' | 'S'; price: number; status: 'AVAILABLE' | 'HELD' | 'RESERVED';
};
export type HoldDto = {
  id: string; performanceId: number; expiresAt: string; status: 'ACTIVE';
  seats: SeatDto[]; total: number; currency: 'KRW';
};
type AuthDto = { accessToken: string; tokenType: 'Bearer'; expiresAt: string; user: AuthUser };
type ErrorDto = { code: string; message: string; details: Record<string, unknown>; traceId: string };
export class ApiError extends Error {
  constructor(public status: number, public data: ErrorDto) { super(data.message); }
}
const BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1';
let access: string | null = null;
let refreshing: Promise<AuthDto> | null = null;

async function parse<T>(response: Response): Promise<T> {
  if (!response.ok) throw new ApiError(response.status, await response.json() as ErrorDto);
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}
export async function login(username: string, password: string): Promise<AuthUser> {
  const auth = await parse<AuthDto>(await fetch(`${BASE}/auth/login`, {
    method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  }));
  access = auth.accessToken;
  return auth.user;
}
export async function restoreSession(): Promise<AuthUser> {
  if (!refreshing) refreshing = fetchResult().then(response => parse<AuthDto>(response)).then(auth => {
    access = auth.accessToken; return auth;
  }).finally(() => { refreshing = null; });
  return (await refreshing).user;
}
// Separate async helper keeps the fetch response fully resolved before parsing.
async function fetchResult(): Promise<Response> {
  return fetch(`${BASE}/auth/refresh`, { method: 'POST', credentials: 'include' });
}
export async function api<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers);
  if (access) headers.set('Authorization', `Bearer ${access}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${BASE}${path}`, { ...init, credentials: 'include', headers });
  if (response.status === 401 && retry) {
    await restoreSession();
    return api<T>(path, init, false);
  }
  return parse<T>(response);
}
export async function logout(): Promise<void> {
  try { await api<void>('/auth/logout', { method: 'POST' }); }
  finally { access = null; }
}

// Use the current i18n translation function; persist language-independent codes.
export function mapEvent(dto: EventDto, t: (key: string) => string) {
  return {
    id: dto.id, title: dto.title, category: t(`category.${dto.category}`),
    venue: dto.venue ?? '', date: dto.date, price: dto.price,
    headline: dto.description, posterUrl: dto.posterUrl,
    status: t(`eventStatus.${dto.status}`), canBook: dto.status === 'ON_SALE',
    // art was a mock CSS identifier. Use posterUrl in <img> and a local fallback when null.
  };
}
export function mapSeat(dto: SeatDto, ownHoldSeatIds = new Set<string>()) {
  return {
    id: dto.id, row: dto.row, number: dto.number, grade: dto.grade, price: dto.price,
    reserved: dto.status !== 'AVAILABLE' && !ownHoldSeatIds.has(dto.id),
    selected: ownHoldSeatIds.has(dto.id), x: dto.x, y: dto.y,
  };
}
export function mapFaq(dto: { id: number; title: string; content: string }) {
  return { id: dto.id, question: dto.title, answer: dto.content };
}
export function mapPost(dto: { id: number; category: string; title: string; content: string; date: string }) {
  return dto; // Translate category NOTICE/GUIDE/UPDATE and format date only when rendering.
}
export async function listEvents(lang: 'ko' | 'en' | 'zh-CN', page = 1, q = '') {
  const params = new URLSearchParams({ lang, page: String(page), pageSize: '12', q, sort: 'RECOMMENDED' });
  return api<Page<EventDto>>(`/events?${params}`);
}
export async function joinQueue(performanceId: number) {
  return api<QueueDto>(`/performances/${performanceId}/queue`, { method: 'POST' });
}
// Poll recursively only after the previous call finishes; cancel on component cleanup.
export function pollQueue(performanceId: number, onState: (state: QueueDto) => void,
  onError: (error: unknown) => void) {
  let stopped = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const controller = new AbortController();
  async function next() {
    try {
      const state = await api<QueueDto>(`/performances/${performanceId}/queue/me`, { signal: controller.signal });
      if (stopped) return;
      onState(state);
      if (state.state === 'WAITING') timer = setTimeout(next, state.pollAfterSeconds * 1000);
    } catch (error) { if (!stopped) onError(error); }
  }
  void next();
  return () => { stopped = true; if (timer) clearTimeout(timer); controller.abort(); };
}
export async function holdSeats(performanceId: number, admissionToken: string, seatIds: string[]) {
  const numericIds = seatIds.map(id => Number(id));
  if (numericIds.some(id => !Number.isSafeInteger(id) || id <= 0)) throw new Error('Invalid seat id');
  return api<HoldDto>(`/performances/${performanceId}/holds`, {
    method: 'POST', headers: { 'X-Admission-Token': admissionToken },
    body: JSON.stringify({ seatIds: numericIds }),
  });
}
// Generate the key ONCE per hold confirmation action. Reuse it for network retries.
export function createReservation(holdId: string, admissionToken: string, idempotencyKey: string) {
  return api<{ id: string; status: 'CONFIRMED' | 'CANCELLED'; paymentStatus: 'NOT_REQUIRED'; total: number }>('/reservations', {
    method: 'POST', headers: { 'X-Admission-Token': admissionToken, 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ holdId }),
  });
}

import { TOPICS, type Topic } from "./types";

const URL = "/v1/events";
const RETRY_MS = 3000;

interface Subscriber {
  onTopic: (t: Topic) => void;
  onReconnect: () => void;
}

// One EventSource is shared by every subscriber (browsers cap connections per origin).
const subscribers = new Set<Subscriber>();
let source: EventSource | undefined;
let retryTimer: ReturnType<typeof setTimeout> | undefined;
let dropped = false;

function open(): void {
  const es = new EventSource(URL);
  source = es; // `dropped` survives a re-open so the next open still counts as a reconnect
  es.addEventListener("changed", (e) => {
    try {
      const topic = (JSON.parse((e as MessageEvent).data) as { topic?: string }).topic;
      if (TOPICS.includes(topic as Topic)) for (const s of [...subscribers]) s.onTopic(topic as Topic);
    } catch {
      /* malformed frame: ignore */
    }
  });
  es.addEventListener("error", () => {
    dropped = true;
    // CLOSED means the browser gave up (e.g. an HTTP error); the built-in retry only covers dropped connections.
    if (es.readyState === 2 && source === es && !retryTimer) {
      es.close();
      retryTimer = setTimeout(() => {
        retryTimer = undefined;
        if (subscribers.size > 0) open();
      }, RETRY_MS);
    }
  });
  es.addEventListener("open", () => {
    if (!dropped) return;
    dropped = false;
    for (const s of [...subscribers]) s.onReconnect();
  });
}

function close(): void {
  if (retryTimer) clearTimeout(retryTimer);
  retryTimer = undefined;
  source?.close();
  source = undefined;
  dropped = false;
}

/**
 * Subscribes to server events. `onTopic` fires for each `changed` event; `onReconnect` fires once each time the
 * connection comes back after an error (events may have been missed, so refetch). Returns the unsubscribe function.
 */
export function connectEvents(onTopic: (t: Topic) => void, onReconnect: () => void): () => void {
  const sub: Subscriber = { onTopic, onReconnect };
  subscribers.add(sub);
  if (!source && !retryTimer) open();
  return () => {
    if (!subscribers.delete(sub)) return;
    if (subscribers.size === 0) close();
  };
}

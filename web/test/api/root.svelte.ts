// Runes are only available in .svelte.ts files, so tests reach $effect.root through this helper.
export function root(fn: () => void): () => void {
  return $effect.root(fn);
}

/**
 * The largest whole-pixel size from `max` down to `min` at which `fits` holds, or `min` when none does (the text is then
 * clamped with an ellipsis). The card title uses it so a long name shrinks to show whole before it is ever cut.
 */
export function fitSize(fits: (px: number) => boolean, max = 26, min = 17): number {
  for (let px = max; px > min; px--) if (fits(px)) return px;
  return min;
}

/** Svelte action: steps the node's font size down until its clamped text no longer overflows. Re-fits once fonts load, on resize and on update. */
export function fitText(node: HTMLElement, _text: string) {
  const fit = () => {
    const size = fitSize((px) => {
      node.style.fontSize = `${px}px`;
      // With a line height of 1 the glyphs poke out a few px; only a hidden extra line (most of a line) is overflow.
      return node.scrollHeight <= node.clientHeight + px * 0.4;
    });
    node.style.fontSize = `${size}px`;
  };
  fit();
  // The first fit can run on the fallback font; fit again once the real one has loaded.
  document.fonts?.ready.then(fit);
  const ro = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(fit);
  ro?.observe(node);
  return {
    update: fit,
    destroy: () => ro?.disconnect(),
  };
}

/** A card-title size: font size in px and how many lines it may take. */
export type TitleStep = { px: number; lines: number };

/**
 * The card title's sizes, tried in order: two lines from 26 px down to 18, then a third line from 20 px down to 17.
 * Only past the last is the title cut with an ellipsis. The app uses the same steps.
 */
export const TITLE_STEPS: TitleStep[] = [
  ...Array.from({ length: 9 }, (_, i) => ({ px: 26 - i, lines: 2 })),
  ...Array.from({ length: 4 }, (_, i) => ({ px: 20 - i, lines: 3 })),
];

/** The first step at which `fits` holds, or the last step when none does. */
export function fitStep(fits: (s: TitleStep) => boolean, steps: TitleStep[] = TITLE_STEPS): TitleStep {
  return steps.find(fits) ?? steps[steps.length - 1];
}

/** Svelte action: steps the node's size (and line clamp) through TITLE_STEPS until its text no longer overflows. */
export function fitText(node: HTMLElement, _text: string) {
  const apply = (s: TitleStep) => {
    node.style.fontSize = `${s.px}px`;
    node.style.setProperty("-webkit-line-clamp", String(s.lines));
    node.style.setProperty("line-clamp", String(s.lines));
  };
  const fit = () =>
    apply(
      fitStep((s) => {
        apply(s);
        // With a line height of 1 the glyphs poke out a few px; only a hidden extra line (most of a line) is overflow.
        return node.scrollHeight <= node.clientHeight + s.px * 0.4;
      }),
    );
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

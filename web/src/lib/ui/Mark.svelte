<script lang="ts">
  /**
   * The Tandem mark, "two marks": a filled square beside an outlined one, the app's own "has the newest" and "has a
   * copy". Drawn in currentColor on the 48-unit grid of the icon, cropped to the squares, `size` px tall.
   */
  let { size = 13 }: { size?: number } = $props();
</script>

<svg class="mark" width={(size * 38) / 20} height={size} viewBox="5 14 38 20" aria-hidden="true">
  <rect x="5" y="14" width="18" height="20" fill="currentColor" />
  <rect x="26.5" y="15.5" width="15" height="17" fill="none" stroke="currentColor" stroke-width="3" />
</svg>

<style>
  .mark {
    flex: none;
    display: block;
  }
</style>

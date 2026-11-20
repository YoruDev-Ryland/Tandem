<script lang="ts">
  import { PRODUCT_NAME } from "./lib/brand";
</script>

<main>
  <h1>{PRODUCT_NAME}</h1>
</main>

<style>
  main {
    padding: 0 40px;
    height: 72px;
    display: flex;
    align-items: center;
  }
  h1 {
    margin: 0;
    font-family: "Barlow Semi Condensed", sans-serif;
    font-weight: 600;
    font-size: 28px;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
</style>

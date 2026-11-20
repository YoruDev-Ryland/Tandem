<script lang="ts">
  let { text }: { text: string } = $props();
</script>

<p>{text}</p>

<style>
  p {
    margin: 0;
    padding: 12px 0;
    color: var(--ink2);
    font-size: 15px;
  }
</style>

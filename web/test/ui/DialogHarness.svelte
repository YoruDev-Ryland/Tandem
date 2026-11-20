<script lang="ts">
  import Dialog from "../../src/lib/ui/Dialog.svelte";

  let open = $state(false);
</script>

<button type="button" onclick={() => (open = true)}>Rename</button>
<Dialog bind:open title="Rename device">
  <p>Inside</p>
  <button type="button" onclick={() => (open = false)}>Done</button>
</Dialog>

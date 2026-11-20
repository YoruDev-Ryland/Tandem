<script lang="ts">
  import List from "../../src/lib/ui/List.svelte";

  let { rows, onOpen }: { rows: { name: string }[]; onOpen: (r: { name: string }) => void } = $props();
</script>

<List {rows} columns="1fr" header={["Game"]} {onOpen}>
  {#snippet row(r)}<span>{r.name}</span>{/snippet}
</List>

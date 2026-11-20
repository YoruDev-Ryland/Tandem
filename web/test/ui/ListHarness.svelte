<script lang="ts">
  import List from "../../src/lib/ui/List.svelte";

  let { rows, onOpen, key }: { rows: { name: string }[]; onOpen: (r: { name: string }) => void; key?: (r: { name: string }) => unknown } = $props();
</script>

<List {rows} columns="1fr" header={["Game"]} {onOpen} {key}>
  {#snippet row(r)}<span>{r.name}</span>{/snippet}
</List>

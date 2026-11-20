<script lang="ts">
  import { describeError } from "./errors";

  let { error }: { error: unknown } = $props();
</script>

<p class="fault" role="alert">{describeError(error)}</p>

<style>
  p {
    margin: 0;
    color: var(--fault);
    font-size: 15px;
  }
</style>

<script lang="ts">
  import { getTheme, setTheme, type Theme } from "../../lib/theme";

  let theme = $state<Theme>(getTheme());
  const OPTIONS: { value: Theme; label: string }[] = [
    { value: "system", label: "System" },
    { value: "light", label: "Light" },
    { value: "dark", label: "Dark" },
  ];
</script>

<div class="sec">
  <h2>Appearance</h2>
  <fieldset>
    <legend>Theme</legend>
    {#each OPTIONS as o (o.value)}
      <label class="check">
        <input type="radio" name="theme" value={o.value} bind:group={theme} onchange={() => setTheme(o.value)} />
        {o.label}
      </label>
    {/each}
  </fieldset>
</div>

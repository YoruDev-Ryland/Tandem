<script lang="ts">
  import { tick } from "svelte";
  import { ApiError } from "../lib/api/http";
  import { session } from "../lib/session.svelte";
  import { PRODUCT_NAME } from "../lib/brand";

  const messages: Record<string, string> = {
    invalid_credentials: "That email or password is not right. Check them and try again.",
    code_not_found: "That code was not found. Check it and try again.",
    code_used: "That code was already used. Get a new one.",
    code_expired: "That code has expired. Get a new one.",
  };

  function describe(e: unknown): string {
    if (e instanceof ApiError) return messages[e.code] ?? e.message;
    return "Something went wrong. Try again.";
  }

  const host = location.host;

  let mode = $state<"password" | "code">("password");
  let email = $state("");
  let password = $state("");
  let code = $state("");
  let credentialsError = $state("");
  let codeError = $state("");
  let busy = $state(false);
  let first: HTMLInputElement | undefined = $state();

  async function submit(req: Parameters<typeof session.signIn>[0], fail: (m: string) => void) {
    busy = true;
    fail("");
    try {
      await session.signIn(req);
    } catch (e) {
      fail(describe(e));
    } finally {
      busy = false;
    }
  }

  function withCredentials(e: SubmitEvent) {
    e.preventDefault();
    void submit({ email, password }, (m) => (credentialsError = m));
  }

  function withCode(e: SubmitEvent) {
    e.preventDefault();
    void submit({ pairingCode: code }, (m) => (codeError = m));
  }

  function normalizeCode(e: Event & { currentTarget: HTMLInputElement }) {
    code = e.currentTarget.value.toUpperCase().slice(0, 8);
    e.currentTarget.value = code;
  }

  async function swap() {
    mode = mode === "password" ? "code" : "password";
    await tick();
    first?.focus();
  }
</script>

<main>
  <div class="col">
    <h1>{PRODUCT_NAME}</h1>
    <p class="host mono">{host}</p>

    {#if mode === "password"}
      <form onsubmit={withCredentials} aria-label="Sign in with email and password">
        <label>Email<input bind:this={first} type="email" autocomplete="username" required bind:value={email} /></label>
        <label>Password<input type="password" autocomplete="current-password" required bind:value={password} /></label>
        {#if credentialsError}<p class="error" role="alert">{credentialsError}</p>{/if}
        <button class="primary" type="submit" disabled={busy}>Sign in</button>
      </form>
    {:else}
      <form onsubmit={withCode} aria-label="Sign in with a pairing code">
        <p class="hint">Get a code from the app on a handheld, or from another browser that is signed in.</p>
        <label>
          Pairing code
          <input
            bind:this={first}
            class="mono code"
            autocomplete="off"
            autocapitalize="characters"
            spellcheck="false"
            maxlength="8"
            required
            value={code}
            oninput={normalizeCode}
          />
        </label>
        {#if codeError}<p class="error" role="alert">{codeError}</p>{/if}
        <button class="primary" type="submit" disabled={busy || code.length !== 8}>Sign in with code</button>
      </form>
    {/if}

    <button type="button" class="swap" onclick={swap}>{mode === "password" ? "or use a pairing code" : "or use email and password"}</button>
  </div>
</main>

<style>
  main {
    display: flex;
    justify-content: center;
    min-height: 100vh;
    padding: 0 var(--gutter);
  }
  .col {
    width: 100%;
    max-width: 400px;
    padding: 18vh 0 48px;
  }
  h1 {
    margin: 0;
    font: 700 40px/1 "Barlow Semi Condensed", sans-serif;
    letter-spacing: -0.01em;
    color: var(--ink);
  }
  .host {
    margin: 10px 0 40px;
    color: var(--ink3);
    overflow-wrap: anywhere;
  }
  .mono {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
  }
  form {
    display: flex;
    flex-direction: column;
    gap: 18px;
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 6px;
    width: 100%;
    color: var(--ink2);
    font-size: 14px;
  }
  input {
    font: 500 15px "Barlow", system-ui, sans-serif;
    color: var(--ink);
    background: transparent;
    border: 1.5px solid var(--rule2);
    border-radius: 3px;
    height: 40px;
    padding: 0 10px;
  }
  input:hover {
    border-color: var(--ink3);
  }
  input.code {
    font: 400 15px "JetBrains Mono", ui-monospace, monospace;
    letter-spacing: 0.2em;
  }
  button {
    font: 600 14px "Barlow", system-ui, sans-serif;
    height: 40px;
    padding: 0 18px;
    border-radius: 3px;
    border: 1.5px solid var(--rule2);
    background: transparent;
    color: var(--ink);
    cursor: pointer;
  }
  button.primary {
    align-self: stretch;
    margin-top: 6px;
    background: var(--ink);
    border-color: var(--ink);
    color: var(--bg);
  }
  button.primary:hover:not(:disabled) {
    background: color-mix(in srgb, var(--ink) 86%, var(--bg));
  }
  button:disabled {
    color: var(--ink3);
    cursor: default;
  }
  button.primary:disabled {
    background: transparent;
    border-color: var(--rule2);
  }
  .swap {
    display: block;
    height: auto;
    margin: 24px auto 0;
    padding: 4px 6px;
    border: 0;
    color: var(--ink2);
    font-weight: 500;
    text-decoration: underline;
    text-underline-offset: 3px;
    text-decoration-color: var(--rule2);
  }
  .swap:hover {
    color: var(--ink);
    text-decoration-color: currentColor;
  }
  .hint {
    margin: 0;
    color: var(--ink2);
    font-size: 15px;
  }
  .error {
    margin: 0;
    color: var(--fault);
  }
</style>

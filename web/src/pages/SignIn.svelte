<script lang="ts">
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

  let email = $state("");
  let password = $state("");
  let code = $state("");
  let credentialsError = $state("");
  let codeError = $state("");
  let busy = $state(false);

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
</script>

<main>
  <h1>{PRODUCT_NAME}</h1>
  <div class="forms">
    <form onsubmit={withCredentials}>
      <h2>Email and password</h2>
      <label>Email<input type="email" autocomplete="username" required bind:value={email} /></label>
      <label>Password<input type="password" autocomplete="current-password" required bind:value={password} /></label>
      {#if credentialsError}<p class="error" role="alert">{credentialsError}</p>{/if}
      <button class="primary" type="submit" disabled={busy}>Sign in</button>
    </form>

    <form onsubmit={withCode}>
      <h2>Pairing code</h2>
      <p class="hint">Get a code from the app or another signed-in browser.</p>
      <label>
        Code
        <input
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
      <button type="submit" disabled={busy || code.length !== 8}>Sign in with code</button>
    </form>
  </div>
</main>

<style>
  main {
    padding: 0 40px 40px;
  }
  h1 {
    margin: 0;
    height: 72px;
    display: flex;
    align-items: center;
    font-family: "Barlow Semi Condensed", sans-serif;
    font-weight: 600;
    font-size: 28px;
    letter-spacing: -0.01em;
  }
  .forms {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 360px));
    gap: 56px;
    margin-top: 24px;
  }
  form {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }
  h2 {
    margin: 0;
    font-family: "Barlow Semi Condensed", sans-serif;
    font-weight: 600;
    font-size: 20px;
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 6px;
    width: 100%;
    color: var(--ink3);
    font-size: 13px;
  }
  input {
    font: 500 14px "Barlow", system-ui, sans-serif;
    color: var(--ink);
    background: transparent;
    border: 1.5px solid var(--rule2);
    border-radius: 3px;
    height: 40px;
    padding: 0 10px;
  }
  input.code {
    font: 400 13px "JetBrains Mono", ui-monospace, monospace;
    letter-spacing: 0.1em;
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
    background: var(--ink);
    border-color: var(--ink);
    color: var(--bg);
  }
  button:disabled {
    color: var(--ink3);
    cursor: default;
  }
  button.primary:disabled {
    background: transparent;
    border-color: var(--rule2);
  }
  .hint {
    margin: 0;
    color: var(--ink2);
  }
  .error {
    margin: 0;
    color: var(--fault);
  }
</style>

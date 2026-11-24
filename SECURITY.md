# Security

Tandem holds your saves, your emulator settings (which can include account tokens) and keys that give a device full
access to your account, so security reports are very welcome.

## Reporting a problem

Please report it privately through
[GitHub's security advisories](https://github.com/YoruDev-Ryland/Tandem/security/advisories/new), not in a public issue.
Include what an attacker needs (network access, an account, a paired device), what they can do, and how to reproduce it.
You'll get an answer within a week, and a fix is released as soon as it is ready.

## Supported versions

Only the latest release of the server and the app gets security fixes.

## Running a server safely

- Create your own account right after the first start: the first account becomes the admin. Or set `ADMIN_EMAIL`.
- Reach the server over Tailscale or HTTPS, not plain http on a network you don't control. See
  [Choosing how devices reach the server](README.md#choosing-how-devices-reach-the-server).
- Encrypt backups if they are stored anywhere others can read them: they contain config secrets.
- Only give accounts to people you trust: notification targets can point at addresses inside your network.

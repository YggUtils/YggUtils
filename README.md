# YggUtils

**Run authlib-injector on any server.**
Built for hosts that don't expose JVM arguments.

> YggUtils wraps your Minecraft server JAR and injects [authlib-injector](https://github.com/yushijinhun/authlib-injector) automatically, enabling custom authentication on environments where you can't set JVM flags directly.

---

## Features

- **Authlib Injector Fetching** — automatically retrieves the latest Authlib Injector from the web at runtime.
- **Dynamic Launch Configuration** — handles the server launch, controlled by a minimal config file.
- **Native Console Interface** — mirrors a real server console with instant input and live output.

---

## Requirements

- Java 21+
- Minecraft Server

---

## Getting Started

```bash
java -jar YggUtils.jar
```

On first run, an interactive installer will ask for:
1. Your custom authentication server URL
2. Which server `.jar` to launch (auto-detected)

Config is saved to `YggUtils.yml` and reused on every subsequent run.

---

## Config

```yaml
authserver: https://your.auth.server
serverfile: server.jar
```

To reconfigure, delete `YggUtils.yml` and re-run.

---

## Files

| Path | Description |
|---|---|
| `YggUtils.yml` | Auth server URL and server JAR name |
| `meta/authlibinjector.jar` | Auto-downloaded on first run |

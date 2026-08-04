# Commands and Environment -- Synapse

## Scripts

```bash
# Build the project
./gradlew build

# Run the application
./gradlew :synapse:host:run          # from monorepo root
./gradlew :host:run                  # from synapse/ directory

# Run tests
./gradlew test

# Clean build
./gradlew clean

# Check for dependency updates
./gradlew versionCatalogUpdate
```

## Local Dev Setup

1. Ensure JDK 17+ is installed
2. Start PostgreSQL: `docker-compose up -d` (from repo root)
3. Run `./gradlew :synapse:host:run` to start the server
4. Configuration is in `host/src/main/resources/application.yaml`

## Environment Variables

### Database

| Variable | Purpose | Default |
|----------|---------|---------|
| `DATA_USER` | Database username | **Required** |
| `DATA_PASSWORD` | Database password | **Required** |

### Security

| Variable | Purpose | Default |
|----------|---------|---------|
| `SECURITY_ENABLED` | Enable OIDC auth | `false` |
| `SECURITY_ISSUER` | OIDC Provider URL | (empty) |
| `SECURITY_CLIENTID` | OIDC Client ID | (empty) |
| `SECURITY_CLIENTSECRET` | OIDC Client Secret | (empty) |

### Server

| Variable | Purpose | Default |
|----------|---------|---------|
| `TLSENABLED` | Enable TLS/HTTPS | `true` |
| `CORS_ALL` | Allow all CORS origins | `false` |

## Runtime Notes

- PostgreSQL is required. Use `docker-compose up -d` from the repo root.
- The `/catalyst` route is defined but not currently wired in `Routes.kt`.

# Single-instance deployment

Run one application instance behind Nginx, with PostgreSQL and Redis. Run `mvn clean verify` before installing the service. The STOMP broker runs inside the application and does not distribute events between instances.

## Host and dependencies

Use a host with Java 21, Redis 7+, Nginx and systemd. Provision PostgreSQL 16 separately, with persistent storage, backups and a migration role permitted to create `btree_gist`. Flyway creates the extension and applies V1-V10 at startup. Use TLS for a remote PostgreSQL connection. Keep database ports and Redis inaccessible from the public network.

Configure the local Redis service to bind loopback, retain protected mode, enable `appendonly yes` and `appendfsync always`, and use `maxmemory-policy noeviction`. Allocate sufficient memory and monitor disk space and persistence errors. Restart the service and verify `redis-cli ping` returns `PONG`. Revocation records share this Redis instance with the availability cache. Deleting its data or evicting revocation keys can restore access to a logged-out token until its original expiry. Persistence configuration reduces that risk; it does not replace backups or recovery testing. See the [Redis persistence documentation](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/) for the durability and latency trade-off.

Do not expose port 8080 directly. The application binds `127.0.0.1` by default. Permit external HTTP/HTTPS only through the reverse proxy and restrict SSH to your administration network.

## Build and transfer

On a Docker-enabled build machine:

```bash
mvn -B -ntp clean verify
scp target/tempo.jar deploy/tempo.env.example deploy/tempo.service deploy/nginx.conf ubuntu@YOUR-HOST:/tmp/
```

This explicitly transfers all templates used below; the server does not need a repository checkout. On the host:

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin tempo
sudo install -d -o tempo -g tempo /opt/tempo
sudo install -d -o root -g tempo -m 750 /etc/tempo
sudo install -o tempo -g tempo -m 640 /tmp/tempo.jar /opt/tempo/tempo.jar
sudo install -o root -g tempo -m 640 /tmp/tempo.env.example /etc/tempo/tempo.env
sudoedit /etc/tempo/tempo.env
```

Fill in the JDBC URL, database credentials, Redis URL, signing secret and actual HTTPS browser origin. Generate a private random signing secret of at least 32 bytes. Keep `SEED_ADMIN_PASSWORD` empty for normal startup. Never commit the populated file or print it into logs. The root-owned environment file is read by systemd; it is also group-readable for the explicit setup step below.

## Optional disposable demonstration data

For a demonstration environment only, temporarily set a random `SEED_ADMIN_PASSWORD` of 8-72 UTF-8 bytes in `/etc/tempo/tempo.env`. Seed before enabling the normal service, so two JAR processes never compete for port 8080:

```bash
sudo systemd-run --unit=tempo-setup --collect \
  --property=User=tempo --property=Group=tempo \
  --property=WorkingDirectory=/opt/tempo \
  --property=EnvironmentFile=/etc/tempo/tempo.env \
  /usr/bin/java -jar /opt/tempo/tempo.jar --spring.profiles.active=setup
curl --fail http://127.0.0.1:8080/health
sudo journalctl -u tempo-setup --no-pager
```

The setup profile starts the application and seeds `admin@tempo.africa`, one space, five desks and two rooms. Readiness does not establish seed completion: confirm the seed-completion log before continuing. Then stop the temporary service and clear `SEED_ADMIN_PASSWORD` from the environment file:

```bash
sudo systemctl stop tempo-setup
sudoedit /etc/tempo/tempo.env
```

## Start the normal service

```bash
sudo install -o root -g root -m 644 /tmp/tempo.service /etc/systemd/system/tempo.service
sudo systemctl daemon-reload
sudo systemctl enable --now tempo
sudo systemctl status tempo
curl --fail http://127.0.0.1:8080/health
```

`/health` returns 200 only when PostgreSQL and Redis respond. It returns 503 without internal error details if either dependency fails. Application startup or health alone does not prove booking concurrency or WebSocket behavior; repeat the demonstration after deployment.

## Reverse proxy and TLS

Install Nginx using your host's package manager. Copy `/tmp/nginx.conf` into its site configuration directory, replace `server_name _` with your DNS name and enable that site. The template forwards WebSocket upgrade headers to loopback port 8080. Validate with `sudo nginx -t` before reloading.

The checked-in proxy is an HTTP bootstrap template. Configure a trusted TLS certificate and redirect HTTP to HTTPS before external users authenticate. Configure `WEBSOCKET_ALLOWED_ORIGINS` with the exact HTTPS origin used by your client. Verify REST over HTTPS, WebSocket connectivity, forbidden origins, logout and passive-session expiry through the proxy. TLS provisioning and host setup remain operator tasks.

Back up PostgreSQL and Redis, preserve Flyway migration history and rehearse restoration before relying on this deployment. A schema backup cannot restore lost in-process messages. Do not edit applied migration files; add a new migration for future schema changes.

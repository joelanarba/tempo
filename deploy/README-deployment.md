# Deploying Tempo on EC2 (Ubuntu 22.04)

This guide deploys the packaged JAR behind Nginx, managed by systemd.

## 1. Install Java 21

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless
java -version   # expect 21.x
```

## 2. Create a service user and directories

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin tempo
sudo mkdir -p /opt/tempo /etc/tempo
```

## 3. Build and upload the JAR

On your build machine:

```bash
mvn clean package -DskipTests
scp target/tempo.jar ubuntu@YOUR-EC2-HOST:/tmp/tempo.jar
```

On the server:

```bash
sudo mv /tmp/tempo.jar /opt/tempo/tempo.jar
sudo chown -R tempo:tempo /opt/tempo
```

## 4. Configure environment

```bash
sudo cp deploy/tempo.env.example /etc/tempo/tempo.env
sudo nano /etc/tempo/tempo.env      # fill in real values
sudo chmod 600 /etc/tempo/tempo.env
sudo chown root:tempo /etc/tempo/tempo.env
```

`DATABASE_URL` must be a JDBC URL, e.g.
`jdbc:postgresql://ep-xxx.eu-central-1.aws.neon.tech/tempo?sslmode=require`.

### Neon and `btree_gist`

The schema needs the `btree_gist` extension for the booking exclusion constraint.
Neon ships this extension; `V1__enable_extensions.sql` runs
`CREATE EXTENSION IF NOT EXISTS btree_gist;` automatically on the first Flyway
migration, so no manual step is required. (If you ever provision the database with a
restricted role, run that statement once as a role allowed to create extensions.)

## 5. Install the systemd service

```bash
sudo cp deploy/tempo.service /etc/systemd/system/tempo.service
sudo systemctl daemon-reload
sudo systemctl enable --now tempo
sudo systemctl status tempo
sudo journalctl -u tempo -f      # tail logs
```

Flyway applies all migrations on first start.

## 6. Configure Nginx

```bash
sudo apt install -y nginx
sudo cp deploy/nginx.conf /etc/nginx/sites-available/tempo
sudo ln -s /etc/nginx/sites-available/tempo /etc/nginx/sites-enabled/tempo
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
```

The Nginx config **must** forward the WebSocket upgrade headers
(`Upgrade` and `Connection "upgrade"`) for the `/ws` STOMP endpoint to work — these
are already present in `deploy/nginx.conf`. For production, terminate TLS (e.g. with
Certbot) so WebSocket traffic runs over `wss://`.

## 7. Optional: seed development data

Only for non-production environments. Start once with the `setup` profile and a seed
password set:

```bash
SEED_ADMIN_PASSWORD='a-strong-password' \
  java -jar /opt/tempo/tempo.jar --spring.profiles.active=setup
```

This creates the admin `admin@tempo.africa`, one space, five desks and two rooms.

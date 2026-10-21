# WebSocket demonstration

Tempo exposes SockJS at `/ws`. Authenticate over REST and send the access token in the STOMP CONNECT frame. The only permitted subscriptions are `/topic/spaces/{spaceId}/availability` and `/user/queue/waitlist`. All client SEND frames are rejected. Never subscribe to a physical `/queue` destination.

Browser connections must originate from an entry in `WEBSOCKET_ALLOWED_ORIGINS`. The local template allows `http://localhost:8081`. Save the page below outside the public repository, serve its directory with `python -m http.server 8081 --bind 127.0.0.1`, and open `http://localhost:8081/ws-test.html`. A `file://` page does not supply the allowed origin. Keep tokens out of recordings, URLs and saved HTML.

The page uses third-party client libraries from a CDN. It keeps the token in memory, disables STOMP debug logging and displays message bodies without rendering HTML. REST calls remain in Postman; cross-origin browser REST access is not configured.

```html
<!doctype html>
<html lang="en">
<meta charset="utf-8">
<title>Tempo event viewer</title>
<script src="https://cdn.jsdelivr.net/npm/sockjs-client@1.6.1/dist/sockjs.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/@stomp/stompjs@7.0.0/bundles/stomp.umd.min.js"></script>
<label>Space ID <input id="space"></label>
<label>Access token <input id="token" type="password" autocomplete="off"></label>
<button id="connect">Connect</button>
<button id="disconnect">Disconnect</button>
<pre id="log"></pre>
<script>
  const log = text => document.getElementById('log').append(document.createTextNode(text + '\n'));
  let client;
  document.getElementById('connect').onclick = () => {
    if (client) return;
    const token = document.getElementById('token').value.trim();
    const space = document.getElementById('space').value.trim();
    if (!token || !/^[0-9a-f-]{36}$/i.test(space)) return log('Enter a token and space UUID.');
    client = new StompJs.Client({
      webSocketFactory: () => new SockJS('http://localhost:8080/ws'),
      connectHeaders: { Authorization: 'Bearer ' + token },
      reconnectDelay: 0,
      debug: () => {},
      onConnect: () => {
        document.getElementById('token').value = '';
        log('Connected. Registering subscriptions.');
        client.subscribe('/topic/spaces/' + space + '/availability', m => log('availability: ' + m.body));
        client.subscribe('/user/queue/waitlist', m => log('private: ' + m.body));
        log('Fetch an initial REST snapshot. Fetch again after events and on reconnect.');
      },
      onStompError: () => log('STOMP rejected the connection or command.'),
      onWebSocketClose: () => log('Connection closed. Disconnect, then reconnect with a valid token.')
    });
    client.activate();
  };
  document.getElementById('disconnect').onclick = async () => {
    if (client) await client.deactivate();
    client = undefined;
    log('Disconnected.');
  };
</script>
</html>
```

## Interpreting events

Public messages contain `resourceId`, `resourceName`, `startTime`, `endTime` and `state`. `BOOKED` means a booking or hold occupies the interval. `AVAILABLE` means the released interval has no replacement occupancy. `CHANGED` means space/resource metadata changed and has null interval fields. Treat every state as an invalidation signal and fetch a fresh REST snapshot.

Private messages contain `entryId`, `resourceId`, `resourceName`, `spaceId`, `startTime`, `endTime`, `expiresAt` and `state`. `OFFERED` carries the actual deadline. `GRANTED` follows a successful claim. An offer expires no later than the slot start. The expiry sweep marks it expired and attempts the next eligible queue entry; it sends no expiry message to the former recipient. Query `/api/v1/waitlist/mine` to recover current state.

## Registration and reconciliation

The simple broker does not implement STOMP subscription receipts. A CONNECT response alone does not prove that a later SUBSCRIBE is registered. Complete subscriptions before triggering mutations. Automated transport tests wait until the broker reports subscription registration before publishing.

Fetch a snapshot after subscribing, on every event, after reconnect and periodically while showing availability. Compare the snapshot's `revision` and discard a response older than one already applied. This handles delayed HTTP responses and repairs missed messages. There is no durable event cursor, replay or lossless subscription handshake. Events are best-effort hints from one application instance.

## Security checks

Try connecting without a token, with a refresh token, or from an untrusted browser origin. Try subscribing to `/queue/waitlist` and sending a client message to an availability topic. Each must be rejected. A revoked or expired session must stop receiving messages even if it sends no further commands. Reconnect with a valid access token after expiry. Never paste real tokens into shared terminals or issue reports.

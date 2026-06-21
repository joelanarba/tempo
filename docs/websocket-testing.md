# Testing the Realtime Layer

A standard Postman collection cannot exercise STOMP-over-WebSocket, so test the realtime
layer with a tiny browser page (below) or any STOMP client. The flow is:

1. Authenticate over REST to get an access token.
2. Open a STOMP connection to `/ws`, sending the token on the CONNECT frame.
3. Subscribe to `/topic/spaces/{spaceId}/availability`.
4. Create or cancel a booking over REST and watch the message arrive live.

## Option A — Browser page (SockJS + STOMP)

Save this as `ws-test.html`, edit the three constants at the top, and open it in a browser
with the server running.

```html
<!doctype html>
<html>
  <head>
    <script src="https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/@stomp/stompjs@7/bundles/stomp.umd.min.js"></script>
  </head>
  <body>
    <h3>Tempo availability feed</h3>
    <pre id="log"></pre>
    <script>
      const BASE = "http://localhost:8080";
      const TOKEN = "PASTE_ACCESS_TOKEN_HERE";
      const SPACE_ID = "PASTE_SPACE_ID_HERE";

      const log = (m) => (document.getElementById("log").textContent += m + "\n");

      const client = new StompJs.Client({
        webSocketFactory: () => new SockJS(BASE + "/ws"),
        connectHeaders: { Authorization: "Bearer " + TOKEN },
        onConnect: () => {
          log("connected");
          client.subscribe("/topic/spaces/" + SPACE_ID + "/availability", (msg) => {
            log("update: " + msg.body);
          });
        },
        onStompError: (frame) => log("error: " + frame.headers["message"]),
      });
      client.activate();
    </script>
  </body>
</html>
```

With the page connected, create a booking via REST (Postman or curl). A `BOOKED` message
appears immediately; cancelling it produces an `AVAILABLE` message.

Message shape:

```json
{
  "resourceId": "…",
  "resourceName": "Desk 1",
  "startTime": "2026-06-20T09:00:00Z",
  "endTime": "2026-06-20T10:00:00Z",
  "state": "BOOKED"
}
```

## Option B — wscat (raw frames)

`wscat` speaks raw WebSocket, so you must type STOMP frames yourself. Connect to the raw
SockJS websocket transport and send a CONNECT frame (`^@` is a literal NUL byte, Ctrl-@):

```
wscat -c ws://localhost:8080/ws/websocket
> CONNECT
> Authorization:Bearer PASTE_TOKEN
> accept-version:1.2
>
> ^@
> SUBSCRIBE
> id:sub-0
> destination:/topic/spaces/PASTE_SPACE_ID/availability
>
> ^@
```

## Subscribing to waitlist offer notifications

When a slot becomes available and a member is next in the queue, the server sends a private
offer message directly to that member over a user-scoped destination. To receive it,
subscribe to `/user/queue/waitlist` after connecting.

Add this subscription inside `onConnect` in the browser page:

```javascript
client.subscribe("/user/queue/waitlist", (msg) => {
  log("waitlist offer: " + msg.body);
});
```

Or with wscat, after the CONNECT handshake:

```
> SUBSCRIBE
> id:sub-1
> destination:/user/queue/waitlist
>
> ^@
```

This destination is user-scoped: each connected member receives only offers addressed to
them. Other members' offers do not arrive here.

Sample offer message (state `OFFERED`):

```json
{
  "entryId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "resourceId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "resourceName": "Desk 3",
  "startTime": "2026-12-01T09:00:00Z",
  "endTime": "2026-12-01T10:00:00Z",
  "expiresAt": "2026-12-01T09:02:00Z",
  "state": "OFFERED"
}
```

When the member calls `POST /api/v1/waitlist/offers/{entryId}/claim` and the claim succeeds,
a follow-up message with `state: "GRANTED"` arrives on the same destination.

If the offer expires before the member claims it, the server transitions the hold to
`EXPIRED` and cascades to the next waiter. The original recipient receives no further
message on this destination for that entry; they will see the entry in `EXPIRED` status if
they call `GET /api/v1/waitlist/mine`.

## Rejection check

Connecting without a valid `Authorization` header on the CONNECT frame is rejected before
the session is established — the browser page logs an error and never receives messages.

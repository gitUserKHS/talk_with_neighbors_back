# Session security contract

- REST and STOMP authentication use only the `TWN_SESSION` HttpOnly cookie. Session
  credentials must not appear in response headers, browser storage, URLs, or logs.
- The cookie is `SameSite=Lax`. The production deployment derives `Secure=true`
  whenever `PUBLIC_ORIGIN` uses HTTPS.
- CORS allows credentials only for configured origins. SameSite and CORS reduce the
  current cross-site request surface, but they are not a replacement for a complete
  CSRF defense.
- Before any cross-site client requires `SameSite=None`, add and test a synchronizer
  CSRF token (or an equivalent framework-supported strategy) for every unsafe HTTP
  method. Until then, clients must remain same-origin and deployments must keep a
  strict `CORS_ALLOWED_ORIGINS` value.
- STOMP `/topic/**` subscriptions are denied. Room events are delivered through
  authenticated `/user/queue/**` destinations.
- The server revalidates the handshake cookie session on every client
  frame without extending its TTL or writing presence. A server-side transport
  registry closes every socket for the credential during logout, explicit user
  session removal, scheduled expiry cleanup, or an expired-frame check.

The MVC interceptor and AOP authentication paths were removed. Follow-up
refactoring should consolidate the remaining argument resolver and
`BaseController` helpers around Spring Security's principal.

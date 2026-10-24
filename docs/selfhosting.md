# Self-hosting radiolisp

Radiolisp can be hosted in two ways. Both are built from the same code; the
difference is whether a backend is there.

| | Frontend alone | Frontend with backend |
|---|---|---|
| What it is | a static website | the website plus one Java process |
| Search, favourites, playback, import and export | yes | yes |
| Now playing (the title on air) | no | yes |
| `http://` stations on an `https` page | only if they also stream over `https` | yes, through the proxy |
| Runs on | any static file server, at any path | a server where you can run Java 21 |

## Requirements

To build: JDK 21, the [Clojure CLI](https://clojure.org/guides/install_clojure),
[babashka](https://babashka.org/), Node.js 22 and
[dart-sass](https://sass-lang.com/dart-sass/). To run the backend: only a
JDK 21.

Before the first build, install the npm dependencies:

```sh
bb setup
```

## Frontend alone

```sh
bb build:static
```

The website ends up in `dist/static/`. Copy it to any static file server; every
path in it is relative, so it works both at the root of a domain and in a
subfolder (this is what <https://haztecaso.github.io/radiolisp/> serves). To try
it locally:

```sh
cd dist/static && python3 -m http.server 8000
```

Without the backend, the app never shows the title on air, and stations that
only stream over `http://` cannot play when the site is served over `https`.

## Frontend with backend

```sh
bb build
java -jar dist/radiolisp-backend.jar
```

`bb build` writes the website to `dist/public/` and the backend, with all its
dependencies, to `dist/radiolisp-backend.jar`. The jar serves the `public/`
folder next to it, so keep the two together when you copy them to your server.
A single process serves the app, the stream proxy (`/api/stream/<id>`), the
WebSocket for now playing (`/api/ws`) and a health check (`/api/health`).

In production, put it behind a reverse proxy with TLS (Caddy, nginx…) and set
`TRUST_PROXY=true`. Pass the whole site to the backend, or let the reverse
proxy serve `public/` itself and pass only `/api/`. Either way, it must let the
WebSocket upgrade through on `/api/ws`; with nginx, for example:

```nginx
location /api/ws {
    proxy_pass http://127.0.0.1:8090;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

It is configured with environment variables:

| Variable | Default | What it is |
|---|---|---|
| `PORT` | `8090` | port to listen on |
| `HOST` | `127.0.0.1` | address to listen on |
| `TRUST_PROXY` | `false` | `true` to take the client IP from `X-Forwarded-For` (for the per-IP limits) when behind a reverse proxy |
| `LOG_LEVEL` | `info` | `debug` also logs every `/api` request and the titles |

The backend logs to the console which stations it opens and closes, and its
errors. It never logs IP addresses.

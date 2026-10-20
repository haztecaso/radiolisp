(ns radiolisp.backend.guard
  "Stops the backend from being pointed at private networks (SSRF). A
  station in Radio Browser can have any URL, `http://127.0.0.1:…` included,
  so every URL the backend connects to, redirects too, goes through
  `check!` first. `check!` returns the address to connect to, so DNS cannot
  answer differently between the check and the connection (rebinding)."
  (:import (java.net Inet4Address Inet6Address InetAddress URI UnknownHostException)))

(defn- ipv4-in? [^InetAddress address [a b] bits]
  (let [ip   (.getAddress address)
        mask (bit-shift-left -1 (- 32 bits))
        n    (fn [x y] (bit-or (bit-shift-left (bit-and 0xff x) 24) (bit-shift-left (bit-and 0xff y) 16)))]
    (= (bit-and (n (aget ip 0) (aget ip 1)) mask)
       (bit-and (n a b) mask))))

(defn public?
  "False for loopback, private, link-local, CGNAT, unspecified, multicast and
  unique-local addresses."
  [^InetAddress address]
  (not (or (.isLoopbackAddress address)
           (.isAnyLocalAddress address)
           (.isLinkLocalAddress address)
           (.isSiteLocalAddress address)
           (.isMulticastAddress address)
           (and (instance? Inet4Address address)
                (or (ipv4-in? address [0 0] 8)        ; 0.0.0.0/8
                    (ipv4-in? address [100 64] 10)    ; CGNAT
                    (ipv4-in? address [255 255] 16))) ; broadcast
           (and (instance? Inet6Address address)
                (= 0xfc (bit-and 0xfe (aget (.getAddress address) 0))))))) ; fc00::/7

(defn check!
  "The address to connect to for `url`. Throws `ex-info` with `{:reason
  :forbidden}` unless `url` is http(s) and every address its host resolves
  to is public, and `{:reason :unreachable}` when the host does not resolve.
  With `:allow-private?` private addresses pass (tests use a local server)."
  [url {:keys [allow-private?]}]
  (let [uri    (try (URI. url) (catch Exception _ nil))
        scheme (some-> uri .getScheme .toLowerCase)
        host   (some-> uri .getHost)]
    (when-not (and (#{"http" "https"} scheme) host)
      (throw (ex-info "Not an http(s) URL" {:reason :forbidden})))
    (let [addresses (try (InetAddress/getAllByName host)
                         (catch UnknownHostException _
                           (throw (ex-info "Unknown host" {:reason :unreachable}))))]
      (when-not (or allow-private? (every? public? addresses))
        (throw (ex-info "Private address" {:reason :forbidden})))
      (first addresses))))

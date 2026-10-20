(ns radiolisp.backend.icy
  "Shoutcast/Icecast in-band metadata. With `Icy-MetaData: 1` a server that
  supports it answers with `icy-metaint: N`, and the body then repeats: N
  bytes of audio, one length byte L, and L×16 bytes of metadata text such as
  `StreamTitle='Artist - Song';StreamUrl='';`, padded with \\0.

  `feed` is a pure state machine over byte chunks cut anywhere."
  (:require [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.util Arrays)))

(defn initial-state [metaint]
  {:metaint   metaint
   :phase     :audio      ; :audio | :length | :metadata
   :remaining metaint     ; bytes left in the current phase
   :pending   nil})       ; the metadata read so far (a byte array)

(defn- concat-bytes ^bytes [^bytes a ^bytes b]
  (if (nil? a)
    b
    (let [out (Arrays/copyOf a (+ (alength a) (alength b)))]
      (System/arraycopy b 0 out (alength a) (alength b))
      out)))

(defn feed
  "Splits the byte array `data` given the state. Returns `{:state s :audio [bytes …]
  :metadata [bytes …]}`, where each metadata entry is one whole block."
  [state ^bytes data]
  (let [n (alength data)]
    (loop [{:keys [metaint phase remaining pending] :as state} state
           offset   0
           audio    []
           metadata []]
      (if (>= offset n)
        {:state state :audio audio :metadata metadata}
        (let [step (min remaining (- n offset))
              part (Arrays/copyOfRange data (int offset) (int (+ offset step)))]
          (case phase
            :audio
            (recur (if (= step remaining)
                     (assoc state :phase :length :remaining 1)
                     (assoc state :remaining (- remaining step)))
                   (+ offset step) (conj audio part) metadata)

            :length
            (let [size (* 16 (bit-and 0xff (aget data offset)))]
              (recur (if (zero? size)
                       (assoc state :phase :audio :remaining metaint)
                       (assoc state :phase :metadata :remaining size :pending nil))
                     (inc offset) audio metadata))

            :metadata
            (let [block (concat-bytes pending part)]
              (if (= step remaining)
                (recur (assoc state :phase :audio :remaining metaint :pending nil)
                       (+ offset step) audio (conj metadata block))
                (recur (assoc state :remaining (- remaining step) :pending block)
                       (+ offset step) audio metadata)))))))))

(defn- decode ^String [^bytes bs]
  (let [decoder (-> StandardCharsets/UTF_8 .newDecoder
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (try (str (.decode decoder (ByteBuffer/wrap bs)))
         (catch CharacterCodingException _
           (String. bs StandardCharsets/ISO_8859_1)))))

(defn- fields
  "`Key='value';` pairs. A value may hold quotes, so it ends at the last `';`
  before the next `Key='`, or at the end."
  [text]
  (into {} (map (fn [[_ k v]] [k v]))
        (re-seq #"(?s)([A-Za-z_]+)='(.*?)';(?=[A-Za-z_]+='|$)" text)))

(defn stream-title
  "The `StreamTitle` in a metadata block, trimmed: a string (\"\" when the
  station says nothing is on), or nil when there is none or it is an ad."
  [^bytes block]
  (let [text  (str/replace (decode block) #"\u0000+$" "")
        found (fields (str/trimr text))]
    (when-not (or (contains? found "adw_ad") (contains? found "AdCreativeId"))
      (some-> (get found "StreamTitle") str/trim))))

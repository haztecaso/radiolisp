(ns radiolisp.frontend.actions-favorites-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [radiolisp.frontend.actions :as a]
            [radiolisp.frontend.fixtures :as f :refer [blues hls-fm jazz ready run-all]]
            [radiolisp.frontend.store :as store]))

(defn- ids [state]
  (mapv :id (get-in state [:favorites :items])))

(defn- saved [effects]
  (second (f/find-effect effects :storage/save)))

(deftest init-reads-storage-and-asks-for-servers
  (let [{:keys [state effects]} (a/handle store/initial-state [:app/init {:locale :es
                                                                          :theme  {:current :dark :chosen? false}}])]
    (is (= :es (:locale state)))
    (is (= {:current :dark :chosen? false} (:theme state)))
    (is (= [[:dom/lang :es] [:dom/theme :dark] [:audio/volume 1] [:cache/load] [:storage/load] [:last-station/load] [:api/servers] [:sw/register]]
           effects))))

(deftest stations-are-fetched-once-storage-and-servers-are-known
  (let [favorites [{:id "j1" :name "Jazz Radio" :key nil} {:id "zz" :name "Gone FM" :key nil}]]
    (testing "storage first, servers later"
      (let [{:keys [state effects]} (run-all store/initial-state
                                             [:favorites/loaded favorites]
                                             [:api/servers-loaded ["s1"]])]
        (is (= [[:api/stations-by-id {:server "s1" :ids ["j1" "zz"]}]] effects))
        (is (= :loading (get-in state [:favorites :status])))))
    (testing "servers first, storage later"
      (let [{:keys [effects]} (run-all store/initial-state
                                       [:api/servers-loaded ["s1"]]
                                       [:favorites/loaded favorites])]
        (is (= [[:api/stations-by-id {:server "s1" :ids ["j1" "zz"]}]] effects))))
    (testing "the answer fills the stations and marks the missing ones"
      (let [{:keys [state]} (run-all store/initial-state
                                     [:favorites/loaded favorites]
                                     [:api/servers-loaded ["s1"]]
                                     [:stations/loaded [jazz]])]
        (is (= jazz (get-in state [:stations "j1"])))
        (is (= #{"zz"} (get-in state [:favorites :missing])))
        (is (= :done (get-in state [:favorites :status])))))))

(deftest no-favourites-means-no-request
  (let [{:keys [state effects]} (run-all store/initial-state
                                         [:favorites/loaded []]
                                         [:api/servers-loaded ["s1"]])]
    (is (= :done (get-in state [:favorites :status])))
    (is (empty? effects))))

(deftest failed-station-request-retries-then-tells
  (let [loading (:state (run-all store/initial-state
                                 [:favorites/loaded [{:id "j1" :name "Jazz Radio" :key nil}]]
                                 [:api/servers-loaded ["s1" "s2"]]))
        {:keys [state effects]} (a/handle loading [:stations/failed])]
    (is (= [[:api/stations-by-id {:server "s2" :ids ["j1"]}]] effects))
    (let [{:keys [state]} (a/handle state [:stations/failed])]
      (is (= :error (get-in state [:favorites :status])))
      (is (= [[:error/stations {}]] (:toast state))))))

(deftest adding-a-favourite
  (let [state (assoc-in ready [:stations "h1"] hls-fm)
        {:keys [state effects]} (a/handle state [:favorites/toggle "h1"])]
    (is (= ["j1" "b1" "h1"] (ids state)))
    (is (= (get-in state [:favorites :items]) (saved effects)))
    (is (= [[:toast/added {:name "HLS FM"}]] (:toast state)))
    (is (some #{[:timer/start :toast a/toast-ms [:toast/hide]]} effects))))

(deftest removing-asks-first
  (let [{:keys [state effects]} (a/handle ready [:favorites/toggle "j1"])]
    (is (= {:kind :remove :id "j1"} (:dialog state)))
    (is (= [[:dom/show-dialog "dialog"]] effects))
    (testing "cancel keeps it"
      (let [{:keys [state effects]} (a/handle state [:dialog/close])]
        (is (nil? (:dialog state)))
        (is (= ["j1" "b1"] (ids state)))
        (is (= [[:dom/close-dialog "dialog"]] effects))))
    (testing "confirm removes it"
      (let [{:keys [state effects]} (a/handle state [:favorites/remove "j1"])]
        (is (= ["b1"] (ids state)))
        (is (nil? (:dialog state)))
        (is (= [{:id "b1" :name "Blues FM" :key nil}] (saved effects)))
        (is (= [[:toast/removed {:name "Jazz Radio"}]] (:toast state)))))))

(deftest reordering
  (let [{:keys [state effects]} (a/handle ready [:favorites/move-to-front "b1"])]
    (is (= ["b1" "j1"] (ids state)))
    (is (saved effects)))
  (testing "drag and drop"
    (let [{:keys [state effects]} (run-all ready [:drag/start "j1"] [:drag/over :end] [:drag/drop :end])]
      (is (= ["b1" "j1"] (ids state)))
      (is (nil? (:drag state)))
      (is (saved effects)))
    (let [{:keys [state]} (run-all ready [:drag/start "b1"] [:drag/over "j1"])]
      (is (= {:id "b1" :over "j1"} (:drag state)))
      (is (= ["b1" "j1"] (ids (:state (a/handle state [:drag/drop "j1"]))))))
    (is (nil? (:drag (:state (run-all ready [:drag/start "b1"] [:drag/end])))))
    (is (= ready (:state (a/handle ready [:drag/drop "j1"]))) "a drop without a drag is ignored")))

(deftest the-key-dialog
  (let [open (:state (a/handle ready [:dialog/open-key "b1"]))]
    (is (= {:kind :key :id "b1" :pending nil :message nil} (:dialog open)))
    (testing "a free key is proposed"
      (let [{:keys [state]} (a/handle open [:key-dialog/pressed "x"])]
        (is (= "x" (get-in state [:dialog :pending])))
        (is (nil? (get-in state [:dialog :message])))))
    (testing "reserved and non-character keys are refused"
      (let [state (:state (a/handle open [:key-dialog/pressed "j"]))]
        (is (nil? (get-in state [:dialog :pending])))
        (is (= [:key/reserved {:key "j"}] (get-in state [:dialog :message]))))
      (is (= [:key/reserved {:key "␣"}]
             (get-in (:state (a/handle open [:key-dialog/pressed " "])) [:dialog :message])))
      (is (= [:key/invalid {}]
             (get-in (:state (a/handle open [:key-dialog/pressed "F1"])) [:dialog :message]))))
    (testing "a key of another favourite warns that it moves"
      (is (= [:key/taken {:name "Jazz Radio"}]
             (get-in (:state (a/handle open [:key-dialog/pressed "1"])) [:dialog :message]))))
    (testing "saving assigns it"
      (let [{:keys [state effects]} (run-all open [:key-dialog/pressed "1"] [:key-dialog/save])]
        (is (= [nil "1"] (map :key (get-in state [:favorites :items]))))
        (is (nil? (:dialog state)))
        (is (saved effects))
        (is (= [[:toast/key-saved {:key "1" :name "Blues FM"}]] (:toast state)))))
    (testing "saving without a key does nothing"
      (is (= open (:state (a/handle open [:key-dialog/save])))))
    (testing "clearing"
      (let [{:keys [state]} (run-all ready [:dialog/open-key "j1"] [:key-dialog/clear])]
        (is (= [nil nil] (map :key (get-in state [:favorites :items]))))))))

(deftest filtering
  (is (= "blu" (:filter (:state (a/handle ready [:filter/changed "blu"])))))
  (let [{:keys [state effects]} (a/handle ready [:filter/focus])]
    (is (true? (:filter-open? state)) "the field unfolds")
    (is (= [[:dom/focus :filter-input]] effects)))
  (let [{:keys [state effects]} (a/handle (assoc ready :filter "x" :filter-open? true) [:filter/clear])]
    (is (= "" (:filter state)))
    (is (false? (:filter-open? state)))
    (is (= [[:dom/blur :filter-input]] effects)))
  (testing "leaving the field folds it only when empty"
    (is (false? (:filter-open? (:state (a/handle (assoc ready :filter-open? true) [:filter/blurred])))))
    (is (true? (:filter-open? (:state (a/handle (assoc ready :filter "x" :filter-open? true) [:filter/blurred]))))))
  (is (= ["b1"] (mapv :id (a/filtered-favorites (assoc ready :filter "BLUES")))))
  (testing "enter plays the first match"
    (is (= blues (get-in (:state (a/handle (assoc ready :filter "blu") [:filter/play-first]))
                         [:player :station])))
    (is (= jazz (get-in (:state (a/handle ready [:filter/play-first])) [:player :station]))
        "an empty filter plays the first favourite")))

(deftest menus
  (let [menu {:kind :station :id "j1" :anchor "more-j1"}
        {:keys [state effects]} (a/handle ready [:menu/open menu])]
    (is (= menu (:menu state)))
    (is (= [[:dom/place-menu "more-j1"]] effects))
    (testing "the same button closes it"
      (is (nil? (:menu (:state (a/handle state [:menu/open menu]))))))
    (let [{:keys [state effects]} (a/handle state [:menu/close])]
      (is (nil? (:menu state)))
      (is (= [[:dom/focus "more-j1"]] effects)))
    (is (= [[:dom/focus-step "menu" 1]] (:effects (a/handle state [:menu/move 1]))))
    (is (= {:state ready :effects []} (a/handle ready [:menu/page :language]))))
  (testing "the list menu opens its language page in place"
    (let [{:keys [state]} (a/handle ready [:menu/open {:kind :list :anchor "list-menu"}])
          {:keys [state effects]} (a/handle state [:menu/page :language])]
      (is (= :language (get-in state [:menu :page])))
      (is (= [[:dom/place-menu "list-menu"]] effects))
      (is (nil? (get-in (:state (a/handle state [:menu/page nil])) [:menu :page]))))))

(deftest voting-and-copying
  (is (= [[:api/vote {:server "s1" :id "j1"}]] (:effects (a/handle ready [:station/vote "j1"]))))
  (let [{:keys [state]} (a/handle ready [:vote/done "j1" true])]
    (is (= 11 (get-in state [:stations "j1" :votes])))
    (is (= [[:toast/voted {:name "Jazz Radio"}]] (:toast state))))
  (is (= [[:toast/vote-failed {}]] (:toast (:state (a/handle ready [:vote/done "j1" false])))))
  (is (= [[:clipboard/write "http://jazz.example/stream"]]
         (:effects (a/handle ready [:station/copy-url "j1"])))))

(deftest exporting
  (let [opened (:state (a/handle ready [:dialog/open-export]))]
    (is (= {:kind :export :format :m3u :stem "radiolisp-favoritas"} (:dialog opened)))
    (is (= [[:dom/show-dialog "dialog"]] (:effects (a/handle ready [:dialog/open-export]))))
    (testing "M3U carries the favourites with a known stream"
      (let [{:keys [state effects]} (a/handle opened [:export-dialog/save])
            [_ {:keys [filename mime text]}] (f/find-effect effects :file/download)]
        (is (= "radiolisp-favoritas.m3u" filename))
        (is (= "audio/x-mpegurl" mime))
        (is (str/starts-with? text "#EXTM3U\n"))
        (is (str/includes? text "radiobrowser-uuid=\"b1\""))
        (is (nil? (:dialog state)))
        (is (f/find-effect effects :dom/close-dialog))
        (is (= [[:toast/exported {:count 2}]] (:toast state)))))
    (testing "the list carries every uuid, under the chosen name"
      (let [{:keys [state effects]} (run-all opened
                                             [:export-dialog/format :list]
                                             [:export-dialog/stem "mis radios.m3u"]
                                             [:export-dialog/save])
            [_ {:keys [filename mime text]}] (f/find-effect effects :file/download)]
        (is (= "mis radios.list" filename))
        (is (= "text/plain" mime))
        (is (= (apply str (map #(str % "\n") (ids ready))) text))
        (is (= [[:toast/exported {:count (count (ids ready))}]] (:toast state)))))
    (testing "unknown formats are ignored"
      (is (= :m3u (get-in (a/handle opened [:export-dialog/format :pls]) [:state :dialog :format]))))))

(def uuid-a "960e57c5-0601-11e8-ae97-52543be04c81")

(deftest importing
  (let [m3u (str "#EXTM3U\n"
                 "#EXTINF:-1 radiobrowser-uuid=\"j1\",Jazz Radio\nhttp://jazz.example/stream\n"
                 "#EXTINF:-1 radiobrowser-uuid=\"h1\",HLS FM\nhttps://hls.example/live.m3u8\n"
                 "#EXTINF:-1,Somewhere\nhttp://somewhere.example/s\n")]
    (is (= [[:file/pick {:accept ".m3u,.m3u8,.list,.txt,audio/x-mpegurl,audio/mpegurl" :action :import/read}]]
           (:effects (a/handle ready [:favorites/import-pick]))))
    (is (= [[:api/import {:server "s1" :ids ["j1" "h1"] :urls ["http://somewhere.example/s"]
                          :names  {:ids  {"j1" "Jazz Radio" "h1" "HLS FM"}
                                   :urls {"http://somewhere.example/s" "Somewhere"}}}]]
           (:effects (a/handle ready [:import/read m3u "radios.m3u"]))))
    (testing "a list of uuids"
      (is (= [[:api/import {:server "s1" :ids [uuid-a] :urls [] :names {:ids {uuid-a "Mine"} :urls {}}}]]
             (:effects (a/handle ready [:import/read (str "# mine\n" uuid-a " Mine\n") "radios.list"])))))
    (testing "a Radiolise export"
      (is (= [[:api/import {:server "s1" :ids [uuid-a] :urls ["http://somewhere.example/s"]
                            :names  {:ids {uuid-a "A"} :urls {"http://somewhere.example/s" "B"}}}]]
             (:effects (a/handle ready [:import/read (str "version: \"2\"\ntype: list\ndata:\n  Favorites:\n"
                                                          "    - name: A\n      url: http://a.example/s\n      id: " uuid-a "\n"
                                                          "    - name: B\n      url: http://somewhere.example/s\n      id: \"\"\n")
                                        "Favorites_1792522073000.txt"])))))
    (testing "found stations are added once; the toast counts both"
      (let [{:keys [state effects]} (a/handle ready [:import/resolved {:stations [jazz hls-fm] :missing 1}])]
        (is (= ["j1" "b1" "h1"] (ids state)))
        (is (= hls-fm (get-in state [:stations "h1"])))
        (is (saved effects))
        (is (= [[:toast/imported {:count 1}] [:toast/not-found {:count 1}]] (:toast state)))))
    (testing "a name from the file that differs from Radio Browser's becomes the custom name"
      (let [{:keys [state]} (a/handle ready [:import/resolved {:stations [jazz hls-fm (assoc blues :id "x1")]
                                                               :missing  0
                                                               :names    {:ids  {"j1" "Other" "h1" "HLS FM"}
                                                                          :urls {"http://x.example/s" "Found by URL"}}
                                                               :url-ids  {"http://x.example/s" "x1"}}])
            items           (get-in state [:favorites :items])]
        (is (nil? (:custom-name (first items))) "favourites already in the list keep their name")
        (is (= [nil "Found by URL"] (map :custom-name (drop 2 items))))))
    (testing "a file without stations"
      (is (= [[:toast/import-empty {}]] (:toast (:state (a/handle ready [:import/read "hello" "x.m3u"])))))
      (is (= [[:toast/import-empty {}]] (:toast (:state (a/handle ready [:import/read m3u "x.list"]))))))
    (testing "other files are refused"
      (let [{:keys [state effects]} (a/handle ready [:import/read m3u "radios.pls"])]
        (is (empty? (filter #(= :api/import (first %)) effects)))
        (is (= [[:toast/import-unsupported {}]] (:toast state)))))
    (testing "a file dragged over the page shows the drop zone until it leaves"
      (let [{:keys [state effects]} (a/handle ready [:file-drag/over])]
        (is (:file-drag? state))
        (is (= [[:timer/start :file-drag a/file-drag-ms [:file-drag/end]]] effects))
        (is (false? (:file-drag? (:state (a/handle state [:file-drag/end])))))))
    (testing "a dropped file asks first, then imports"
      (let [{:keys [state effects]} (a/handle ready [:import/offer m3u "radios.m3u"])]
        (is (= {:kind :import :filename "radios.m3u" :text m3u :count 3} (:dialog state)))
        (is (= [[:dom/show-dialog "dialog"]] effects))
        (let [{:keys [state effects]} (a/handle state [:import-dialog/confirm])]
          (is (nil? (:dialog state)))
          (is (= [[:dom/close-dialog "dialog"]
                  [:api/import {:server "s1" :ids ["j1" "h1"] :urls ["http://somewhere.example/s"]
                                :names  {:ids  {"j1" "Jazz Radio" "h1" "HLS FM"}
                                         :urls {"http://somewhere.example/s" "Somewhere"}}}]]
                 effects)))))
    (testing "a dropped file that cannot be imported only says so"
      (let [{:keys [state effects]} (a/handle ready [:import/offer nil "photo.png"])]
        (is (nil? (:dialog state)))
        (is (not-any? #(= :dom/show-dialog (first %)) effects))
        (is (= [[:toast/import-unsupported {}]] (:toast state))))
      (is (= [[:toast/import-empty {}]] (:toast (:state (a/handle ready [:import/offer "hello" "x.m3u"])))))))
  (is (= [[:toast/import-failed {}]] (:toast (:state (a/handle ready [:import/failed]))))))

(def logo "https://jazz.example/logo.png")

(deftest artwork
  (let [{:keys [state effects]} (a/handle ready [:art/sized "j1" logo 300 300])]
    (is (= {:w 300 :h 300} (get-in state [:art "j1"])))
    (is (= [[:art/probe "j1" logo]] effects) "the image is probed once")
    (let [{:keys [state effects]} (a/handle state [:art/probed "j1" logo {:alpha? true :dark? true :edge nil}])]
      (is (= {:src logo :alpha? true :dark? true :edge nil} (get-in state [:probes "j1"])))
      (is (= [:cache/save {"j1" {:name "Jazz Radio" :favicon logo :probe {:src logo :alpha? true :dark? true :edge nil}}
                           "b1" {:name "Blues FM" :favicon nil}}]
             (f/find-effect effects :cache/save))
          "the result is cached with the favourite")
      (is (= [] (:effects (a/handle state [:art/sized "j1" logo 300 300]))) "not probed again")))
  (testing "without CORS the extension decides"
    (is (= {:src logo :alpha? true} (get-in (:state (a/handle ready [:art/probed "j1" logo nil])) [:probes "j1"]))))
  (testing "the cache brings the probes back"
    (is (= {"j1" {:src logo :alpha? false}}
           (:probes (:state (a/handle ready [:cache/loaded {"j1" {:name "J" :favicon logo :probe {:src logo :alpha? false}}
                                                            "b1" {:name "B" :favicon nil}}]))))))
  (is (= :broken (get-in (:state (a/handle ready [:art/failed "j1"])) [:art "j1"]))))

(deftest toast-hides
  (let [{:keys [state]} (run-all ready [:toast/show [[:toast/copied {}]]] [:toast/hide])]
    (is (nil? (:toast state)))))

;; ---------------------------------------------------------------- station cache

(defn- cached [effects]
  (second (f/find-effect effects :cache/save)))

(deftest the-cache-is-read-before-the-favourites
  (let [{:keys [effects]} (a/handle store/initial-state [:app/init {:locale :es
                                                                    :theme  {:current :light :chosen? false}}])
        types (f/effect-types effects)]
    (is (< (.indexOf types :cache/load) (.indexOf types :storage/load)))))

(deftest cached-stations-only-show-until-the-api-answers
  (let [cache {"j1" {:name "Jazz Radio (old)" :favicon "https://jazz.example/old.png"}}
        {:keys [state effects]} (run-all store/initial-state
                                         [:cache/loaded cache]
                                         [:favorites/loaded [{:id "j1" :name "Jazz Radio" :key nil}
                                                             {:id "zz" :name "Gone FM" :key nil}]]
                                         [:api/servers-loaded ["s1"]]
                                         [:stations/loaded [jazz]])]
    (testing "the answer replaces the cache and drops what Radio Browser no longer knows"
      (is (= {"j1" {:name "Jazz Radio" :favicon "https://jazz.example/logo.png"}} (:cache state)))
      (is (= (:cache state) (cached effects))))
    (testing "the cache never holds a stream"
      (is (not-any? :url (vals (cached effects)))))))

(deftest an-unchanged-answer-writes-nothing
  (let [cache {"j1" {:name "Jazz Radio" :favicon "https://jazz.example/logo.png"}}
        {:keys [effects]} (run-all store/initial-state
                                   [:cache/loaded cache]
                                   [:favorites/loaded [{:id "j1" :name "Jazz Radio" :key nil}]]
                                   [:api/servers-loaded ["s1"]]
                                   [:stations/loaded [jazz]])]
    (is (nil? (cached effects)))))

(deftest editing-the-favourites-keeps-the-cache-in-step
  (let [state (assoc ready :cache {"j1" {:name "Jazz Radio" :favicon "https://jazz.example/logo.png"}
                                   "b1" {:name "Blues FM" :favicon nil}})]
    (testing "a new favourite goes in"
      (is (contains? (cached (:effects (a/handle state [:favorites/toggle "h1"]))) "h1")))
    (testing "a removed one goes out"
      (is (= #{"j1"} (set (keys (cached (:effects (a/handle state [:favorites/remove "b1"]))))))))
    (testing "moving one changes nothing"
      (is (nil? (cached (:effects (a/handle state [:favorites/move-to-front "b1"]))))))))

(deftest a-cached-station-cannot-play
  (let [state (-> ready
                  (assoc :stations {})
                  (assoc :cache {"j1" {:name "Jazz Radio" :favicon nil}}))]
    (is (nil? (get-in (:state (a/handle state [:favorites/play "j1"])) [:player :station])))))

(deftest customizing
  (let [{:keys [state effects]} (a/handle ready [:customize/open "j1"])]
    (is (= {:kind :customize :id "j1" :name "Jazz Radio" :display nil :color nil} (:dialog state)))
    (is (= [[:dom/show-dialog "dialog"] [:dom/preview-size] [:dom/select "customize-name"]] effects))
    (testing "Guardar keeps the typed name, the chosen look and colour"
      (let [{:keys [state effects]} (run-all state [:customize/name "  Jazz\nclub "] [:customize/display :text]
                                             [:customize/color :ochre] [:customize/save])
            fav (first (get-in state [:favorites :items]))]
        (is (nil? (:dialog state)))
        (is (= "Jazz club" (:custom-name fav)))
        (is (= :text (:display fav)))
        (is (= :ochre (:color fav)))
        (is (= "Jazz club" (a/favorite-name state "j1")))
        (is (= fav (first (saved effects))))
        (is (some #{[:dom/focus "more-j1"]} effects) "the focus goes back to the tile")
        (testing "the original name comes back from the dialog"
          (let [{:keys [state effects]} (run-all state [:customize/open "j1"] [:customize/use-original] [:customize/save])
                fav (first (get-in state [:favorites :items]))]
            (is (nil? (:custom-name fav)))
            (is (= :text (:display fav)) "an untouched look stays")
            (is (= :ochre (:color fav)) "an untouched colour stays")
            (is (saved effects))))))
    (testing "unknown looks and colours are ignored"
      (is (nil? (get-in (:state (a/handle state [:customize/display :huge])) [:dialog :display])))
      (is (nil? (get-in (:state (a/handle state [:customize/color :magenta])) [:dialog :color]))))
    (testing "the automatic colour takes a chosen one away"
      (let [state (:state (run-all state [:customize/color :red] [:customize/save]))
            state (:state (run-all state [:customize/open "j1"] [:customize/color nil] [:customize/save]))]
        (is (not (contains? (first (get-in state [:favorites :items])) :color)))))
    (testing "closing without saving changes nothing"
      (let [{:keys [state effects]} (run-all state [:customize/name "X"] [:dialog/close])]
        (is (nil? (:dialog state)))
        (is (= "Jazz Radio" (a/favorite-name state "j1")))
        (is (nil? (saved effects)))
        (is (some #{[:dom/focus "more-j1"]} effects)))))
  (is (nil? (:dialog (:state (a/handle ready [:customize/open "zz"])))) "only favourites")
  (is (= [] (:effects (a/handle ready [:customize/save]))) "nothing to save without the dialog"))

(deftest renamed-favourites-show-their-name
  (let [state (assoc-in ready [:favorites :items 0 :custom-name] "Jazz")]
    (is (= {:title "Jazz" :artist "radiolisp" :artwork (:favicon jazz)}
           (second (f/find-effect (:effects (a/handle state [:favorites/play "j1"])) :media/update))))
    (is (= ["j1"] (map :id (a/filtered-favorites (assoc state :filter "jazz"))))))
  (testing "the M3U export carries it"
    (let [download (-> (assoc-in ready [:favorites :items 0 :custom-name] "Jazz")
                       (a/handle [:dialog/open-export]) :state
                       (a/handle [:export-dialog/save]) :effects
                       (f/find-effect :file/download))]
      (is (str/includes? (:text (second download)) ",Jazz\n")))))

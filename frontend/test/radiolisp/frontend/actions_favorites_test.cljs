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
  (let [{:keys [state effects]} (a/handle store/initial-state [:app/init {:locale :es}])]
    (is (= :es (:locale state)))
    (is (= [[:dom/lang :es] [:audio/volume 1] [:storage/load] [:storage/persist] [:api/servers]]
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
    (is (= [[:dom/focus-step "menu" 1]] (:effects (a/handle state [:menu/move 1]))))))

(deftest voting-and-copying
  (is (= [[:api/vote {:server "s1" :id "j1"}]] (:effects (a/handle ready [:station/vote "j1"]))))
  (let [{:keys [state]} (a/handle ready [:vote/done "j1" true])]
    (is (= 11 (get-in state [:stations "j1" :votes])))
    (is (= [[:toast/voted {:name "Jazz Radio"}]] (:toast state))))
  (is (= [[:toast/vote-failed {}]] (:toast (:state (a/handle ready [:vote/done "j1" false])))))
  (is (= [[:clipboard/write "http://jazz.example/stream"]]
         (:effects (a/handle ready [:station/copy-url "j1"])))))

(deftest exporting
  (let [{:keys [state effects]} (a/handle ready [:favorites/export])
        [_ {:keys [filename text]}] (f/find-effect effects :file/download)]
    (is (= "radiolisp-favoritas.m3u" filename))
    (is (str/starts-with? text "#EXTM3U\n"))
    (is (str/includes? text "radiobrowser-uuid=\"b1\""))
    (is (= [[:toast/exported {:count 2}]] (:toast state)))))

(deftest importing
  (let [m3u (str "#EXTM3U\n"
                 "#EXTINF:-1 radiobrowser-uuid=\"j1\",Jazz Radio\nhttp://jazz.example/stream\n"
                 "#EXTINF:-1 radiobrowser-uuid=\"h1\",HLS FM\nhttps://hls.example/live.m3u8\n"
                 "#EXTINF:-1,Somewhere\nhttp://somewhere.example/s\n")]
    (is (= [[:file/pick {:accept ".m3u,.m3u8,audio/x-mpegurl,audio/mpegurl" :action :import/read}]]
           (:effects (a/handle ready [:favorites/import-pick]))))
    (is (= [[:api/import {:server "s1" :ids ["j1" "h1"] :urls ["http://somewhere.example/s"]}]]
           (:effects (a/handle ready [:import/read m3u]))))
    (testing "found stations are added once; the toast counts both"
      (let [{:keys [state effects]} (a/handle ready [:import/resolved {:stations [jazz hls-fm] :missing 1}])]
        (is (= ["j1" "b1" "h1"] (ids state)))
        (is (= hls-fm (get-in state [:stations "h1"])))
        (is (saved effects))
        (is (= [[:toast/imported {:count 1}] [:toast/not-found {:count 1}]] (:toast state)))))
    (testing "a file without stations"
      (is (= [[:toast/import-empty {}]] (:toast (:state (a/handle ready [:import/read "hello"])))))))
  (is (= [[:toast/import-failed {}]] (:toast (:state (a/handle ready [:import/failed]))))))

(deftest artwork
  (is (= :cover (get-in (:state (a/handle ready [:art/sized "j1" 300 300])) [:art "j1"])))
  (is (= :broken (get-in (:state (a/handle ready [:art/failed "j1"])) [:art "j1"]))))

(deftest toast-hides
  (let [{:keys [state]} (run-all ready [:toast/show [[:toast/copied {}]]] [:toast/hide])]
    (is (nil? (:toast state)))))

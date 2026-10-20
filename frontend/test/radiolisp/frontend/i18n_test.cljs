(ns radiolisp.frontend.i18n-test
  (:require [cljs.test :refer [deftest is testing]]
            [radiolisp.frontend.i18n :as i18n]))

(deftest t-interpolates-variables
  (is (= "Pausar Jazz Radio" (i18n/t :es :player/pause {:name "Jazz Radio"})))
  (is (= "Ninguna emisora se llama «xyzzy». Prueba con una parte del nombre."
         (i18n/t :es :search/empty {:query "xyzzy"}))))

(deftest t-picks-the-plural-form
  (is (= "1 emisora" (i18n/t :es :search/results {:count 1})))
  (is (= "12 emisoras" (i18n/t :es :search/results {:count 12})))
  (is (= "1 station" (i18n/t :en :search/results {:count 1})))
  (is (= "12 stations" (i18n/t :en :search/results {:count 12}))))

(deftest t-formats-numbers-for-the-locale
  (is (= "1,678 plays" (i18n/t :en :station/listens {:count 1678})))
  (is (= "12.345 escuchas" (i18n/t :es :station/listens {:count 12345}))))

(deftest t-shows-missing-keys
  (is (= ":nope/missing" (i18n/t :es :nope/missing))))

(deftest dictionaries-have-the-same-keys
  (let [{:keys [es en]} i18n/dictionaries]
    (is (= (set (keys es)) (set (keys en))))
    (doseq [[k v] es :when (map? v)]
      (testing (str k)
        (is (= (set (keys v)) (set (keys (get en k)))))))))

(deftest detect-locale-takes-the-first-supported-language
  (is (= :es (i18n/detect-locale ["es-ES" "en"])))
  (is (= :es (i18n/detect-locale ["ES"])))
  (is (= :en (i18n/detect-locale ["fr-FR" "en-GB"])))
  (is (= :en (i18n/detect-locale ["fr"])))
  (is (= :en (i18n/detect-locale []))))

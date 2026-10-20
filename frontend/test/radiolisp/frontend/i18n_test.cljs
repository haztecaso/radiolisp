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
  (is (= "1,678 stations" (i18n/t :en :search/results {:count 1678})))
  (is (= "12.345 emisoras" (i18n/t :es :search/results {:count 12345}))))

(deftest format-date-shows-day-and-month
  (is (= "17 oct" (i18n/format-date :es "2026-10-17T15:03:57Z")))
  (is (= "Oct 17" (i18n/format-date :en "2026-10-17T15:03:57Z")))
  (is (nil? (i18n/format-date :es nil)))
  (is (nil? (i18n/format-date :es "not a date"))))

(deftest t-shows-missing-keys
  (is (= ":nope/missing" (i18n/t :es :nope/missing))))

(deftest every-locale-has-a-dictionary
  (is (= (set i18n/locales) (set (keys i18n/dictionaries)))))

(deftest dictionaries-have-the-same-keys
  (let [en (:en i18n/dictionaries)]
    (doseq [[locale dictionary] i18n/dictionaries]
      (testing (name locale)
        (is (= (set (keys en)) (set (keys dictionary))))))))

(defn- placeholders [message]
  (set (re-seq #"\{\w+\}" (if (map? message) (apply str (vals message)) message))))

(deftest dictionaries-keep-the-placeholders
  (let [en (:en i18n/dictionaries)]
    (doseq [[locale dictionary] i18n/dictionaries
            [k message] dictionary]
      (testing (str (name locale) " " k)
        (is (= (placeholders (get en k)) (placeholders message)))))))

(deftest plurals-cover-every-form-of-the-locale
  (doseq [[locale dictionary] i18n/dictionaries
          :let [rules (js/Intl.PluralRules. (name locale))
                forms (set (map #(keyword (.select rules %)) (range 200)))]
          [k message] dictionary
          :when (map? message)]
    (testing (str (name locale) " " k)
      (is (every? (set (keys message)) (conj forms :other))))))

(deftest plurals-agree-in-russian
  (is (= "1 станция" (i18n/t :ru :search/results {:count 1})))
  (is (= "3 станции" (i18n/t :ru :search/results {:count 3})))
  (is (= "5 станций" (i18n/t :ru :search/results {:count 5}))))

(deftest detect-locale-takes-the-first-supported-language
  (is (= :es (i18n/detect-locale ["es-ES" "en"])))
  (is (= :es (i18n/detect-locale ["ES"])))
  (is (= :fr (i18n/detect-locale ["fr-FR" "en-GB"])))
  (is (= :pt (i18n/detect-locale ["pt-BR"])))
  (is (= :zh (i18n/detect-locale ["zh-CN"])))
  (is (= :en (i18n/detect-locale ["ja" "en-GB"])))
  (is (= :en (i18n/detect-locale ["ja"])))
  (is (= :en (i18n/detect-locale []))))

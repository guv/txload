; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
; which can be found in the file epl-v1.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns txload.core-test
  (:require
    [clojure.test :refer :all]
    [robert.hooke :as h]
    [txload.core :as tx]
    [clojure.pprint :as pp]
    [clojure.stacktrace :as st]))


(defn cleanup
  []
  (dosync
    (alter (var-get #'clojure.core/*loaded-libs*)      (constantly (sorted-set)))
    (alter (var-get #'txload.core/*finished-ns-loads*) (constantly (sorted-set)))
    (alter (var-get #'txload.core/*pending-ns-loads*)  (constantly {}))))


(defn test-setup
  [f]
  (cleanup)
  (f)
  (cleanup))


(use-fixtures :each test-setup)


(defn counted-txload*
  [load-count-map, txload*, lib, require]
  (swap! load-count-map update-in [lib] (fnil inc 0))
  (txload* lib, require))


(defn counted-load
  [load-count-map, load, & paths]
  (swap! load-count-map
    #(reduce
       (fn [m, p] (update-in m [p] (fnil inc 0)))
       %
       paths))
  (apply load paths))



(defn perform-parallel-loads
  [threads-per-ns, reload-mode, & ns-list]
  (let [txload*-count-map (atom (sorted-map))
        load-count-map (atom (sorted-map))]
    ; when reloading shall be tested, ...
    (when (#{:require-reload :require-reload-all} reload-mode)
      ; ... preload all specified namespaces.
      (doseq [ns ns-list]
        (require ns)))
    (h/add-hook #'txload.core/txload* ::test (partial counted-txload* txload*-count-map))
    (h/add-hook #'clojure.core/load ::test (partial counted-load load-count-map))
    (tx/enable)
    (try
      (let [signal (promise),
            load-fn (case reload-mode
                      :require
                        #(require %)
                      :require-reload
                        #(require % :reload)
                      :require-reload-all
                        #(require % :reload-all))
            ; create waiting threads
            future-coll (vec
                          (for [ns ns-list, _ (range threads-per-ns)]
                            (future
                              (deref signal)
                              (load-fn ns))))]
        ; signal start to all threads
        (deliver signal true)
        ; wait for all threads to finish
        (mapv deref future-coll)
        ; return load count maps
        {:load-count-map @load-count-map,
         :txload*-count-map @txload*-count-map})
      (catch Exception e
        (st/print-cause-trace e)
        e)
      (finally
        (tx/disable)
        (println "\nTesting" reload-mode "...\n")
        (println "clojure.core/load invocations:")
        (pp/pprint @load-count-map)
        (println "txload.core/txload* invocations:")
        (pp/pprint @txload*-count-map)
        (h/remove-hook #'txload.core/txload* ::test)
        (h/remove-hook #'clojure.core/load ::test)))))


(defn all-loaded-once?
  [count-map]
  (every? #(= % 1) (vals count-map)))


(defn check-loaded-once
  [map-or-exception]
  (if (instance? Throwable map-or-exception)
    :exception-encountered
    ; something must have been loaded 
    (let [{:keys [load-count-map, txload*-count-map]} map-or-exception]
      (if (and (seq load-count-map) (seq txload*-count-map))
        ; check that every namespace was loaded once
        (let [load-once?   (all-loaded-once? load-count-map),
              txload-once? (all-loaded-once? txload*-count-map)]
          (cond
            (and load-once? txload-once?) :success
            load-once?   :txload*-multiple-loads
            txload-once? :load-multiple-loads
            :else        :both-multiple-loads))
       :nothing-loaded))))


(defn all-loaded-multiples-of-n?
  [n, count-map]
  (every? #(zero? (mod % n)) (vals count-map)))


(defn check-reload-all
  [n, map-or-exception]
  (if (instance? Throwable map-or-exception)
    :exception-encountered
    ; something must have been loaded 
    (let [{:keys [load-count-map, txload*-count-map]} map-or-exception]
      (if (and (seq load-count-map) (seq txload*-count-map))
        (let [load-correct?   (all-loaded-multiples-of-n? n load-count-map),
              txload-correct? (all-loaded-multiples-of-n? n txload*-count-map)]
          (cond
            (and load-correct? txload-correct?) :success
            load-correct?   :txload*-multiple-loads
            txload-correct? :load-multiple-loads
            :else        :both-multiple-loads))
       :nothing-loaded))))


(def ^:const thread-count 20)

(deftest massive-parallel-require
  (testing "Loading namespaces per `require` with possibly the same dependency namespaces at the same time."
    (is
      (= :success
        (check-loaded-once
          (perform-parallel-loads thread-count, :require, 'debug.inspect, 'debug.trace, 'debug.timing, 'debug.reflect))))))


(deftest massive-parallel-require-reload
  (testing "Reloading namespaces per `require :reload` with possibly the same dependency namespaces at the same time."
    (is
      (= :success
        (check-loaded-once
          (perform-parallel-loads thread-count, :require-reload, 'debug.inspect, 'debug.trace, 'debug.timing, 'debug.reflect))))))


(deftest massive-parallel-require-reload-all
 (testing "Reloading all namespaces per `require :reload-all` with possibly the same dependency namespaces at the same time."
   (is
     (= :success 
       (check-reload-all thread-count,
         (perform-parallel-loads thread-count, :require-reload-all 'debug.inspect, 'debug.trace, 'debug.timing, 'debug.reflect))))))
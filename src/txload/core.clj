; Copyright (c) Gunnar Völkel. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
; which can be found in the file epl-v1.0.txt at the root of this distribution.
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns txload.core
  "Provides transparent transactional loading of Clojure namespaces."
  (:require
    [clojure.string :as str]
    [clojure.set :as set]
    [robert.hooke :as h]))


; keeps track of currently pending loads
(defonce ^:private ^:dynamic *pending-ns-loads* (ref {}))

; keeps track of already loaded namespaces
(defonce ^:private ^:dynamic *finished-ns-loads* (ref (sorted-set)))

; access to private function
(def ^:private root-resource #'clojure.core/root-resource)
(def ^:private throw-if      #'clojure.core/throw-if)

; specifies whether information about every load is printed (complementary to clojure.core/*loading-verbosely*)
(defonce ^:dynamic *verbose* false)


(defonce ^:private enabled-ref (ref false))


(defn- core-loaded-libs-ref
  "Returns the reference bound to c.c/*loaded-libs*."
  []
  (var-get #'clojure.core/*loaded-libs*))


(defmacro var-binding
  "Binds the given value to c.c/*loaded-libs* and executes the body within the binding scope."
  [bindings, & body]
  `(let []
     (push-thread-bindings (hash-map ~@bindings))
     (try
       ~@body
       (finally
         (pop-thread-bindings)))))


(defn- txload*
  "Loads the specified namespace `lib`."
  [lib, require]
  (when *verbose*
    (println "Loading" lib "..."))
  (clojure.core/load (root-resource lib))
  (when *verbose*
    (println "Finished" lib))
  (dosync
    (alter *finished-ns-loads* conj lib)
    (alter *pending-ns-loads* dissoc lib)
    (when require
      (commute (core-loaded-libs-ref) conj lib)))
  true)


(defn- normalize-lib-name
  "Normalize the namespace name of `lib`. Replaces accidental underscores with hyphens."
  [lib]
  (-> lib name (str/replace \_ \-) symbol))


(defn- txload-one
  "Transactional replacement for clojure.core/load-one."
  [lib, need-ns, require]  
  (let [lib (normalize-lib-name lib)]
    ; wait for loaded lib
    (deref
      (dosync
        ; lib already loaded?
        (if (contains? (ensure *finished-ns-loads*) lib)
          ; nothing to do
          (delay true)
          ; lib is currently loading?
          (if-let [pending (get (ensure *pending-ns-loads*) lib)]
            ; return the `delay` corresponding to the load
            pending
            ; create delay that loads the lib
            (let [pending (delay (txload* lib, require))]
              (alter *pending-ns-loads* assoc lib pending)
              pending)))))
    (throw-if (and need-ns (not (find-ns lib)))
      "namespace '%s' not found after loading '%s'"
      lib (root-resource lib))
    true))


(defonce ^:private txload-all-lock (Object.))

(defn- txload-all
  "Transactional replacement for clojure.core/load-all."
  [lib, need-ns, require]  
  (let [lib (normalize-lib-name lib)]
    (when *verbose*
      (println "Loading all starting with" lib "..."))
    (locking txload-all-lock
      (dosync
        (alter *finished-ns-loads* (constantly (sorted-set)))
        (alter *pending-ns-loads*  (constantly {}))
        (txload-one lib, need-ns, require)
        (commute (core-loaded-libs-ref) #(reduce conj % @*finished-ns-loads*))))
    true))


(defn- loading-verbosely?
  []
  (var-get #'clojure.core/*loading-verbosely*))


; taken from clojure.core/load-lib - main goal: remove the contains? check on *loaded-libs*
(defn- txload-lib
  "Replacement for clojure.core/load-lib to replace bad contains? check on *loaded-libs*."
  [prefix, lib, & options]
  (throw-if (and prefix (pos? (.indexOf (name lib) (int \.))))
    "Found lib name '%s' containing period with prefix '%s'. lib names inside prefix lists must not contain periods"
    (name lib) prefix)
  (let [lib (if prefix (symbol (str prefix \. lib)) lib)
        opts (apply hash-map options)
        {:keys [as reload reload-all require use verbose]} opts
        loaded (contains? @*finished-ns-loads* (normalize-lib-name lib))
        load (cond
               reload-all
                 txload-all,
               (or reload (not require) (not loaded))
                 txload-one)        
        need-ns (or as use)
        filter-opts (select-keys opts '(:exclude :only :rename :refer))
        undefined-on-entry (not (find-ns lib))]
    (when reload 
      ; remove from finished set to trigger a reload
      (dosync (alter *finished-ns-loads* disj (normalize-lib-name lib))))
    (var-binding [#'clojure.core/*loading-verbosely* (or (loading-verbosely?) verbose)]
      (if load
        (try
          (load lib need-ns require)
          (catch Exception e
            (when undefined-on-entry
              (remove-ns lib))
            (throw e)))
        (throw-if (and need-ns (not (find-ns lib)))
          "namespace '%s' not found" lib))
      (when (and need-ns (loading-verbosely?))
        (printf "(clojure.core/in-ns '%s)\n" (ns-name *ns*)))
      (when as
        (when (loading-verbosely?)
          (printf "(clojure.core/alias '%s '%s)\n" as lib))
        (alias as lib))
      (when (or use (:refer filter-opts))
        (when (loading-verbosely?)
          (printf "(clojure.core/refer '%s" lib)
          (doseq [opt filter-opts]
            (printf " %s '%s" (key opt) (print-str (val opt))))
          (printf ")\n"))
        (apply refer lib (mapcat seq filter-opts))))))




(defn- wrapped-load-one
  "Hook function which replaces clojure.core/load-one by txload-one."
  [f, lib, need-ns, require]
  (txload-one lib, need-ns, require))


(defn- wrapped-load-all
  "Hook function which replaces clojure.core/load-all by txload-all."
  [f, lib, need-ns, require]
  (txload-all lib, need-ns, require))


(defn- wrapped-load-lib
  "Hook function which replaces clojure.core/load-lib by txload-lib."
  [f, prefix, lib, & options]
  (apply txload-lib prefix, lib, options))


(defn- initialize
  "Initializes the set of already loaded namespaces with the ones in clojure.core/*loaded-libs*."
  []
  (let [loaded-libs-ref (core-loaded-libs-ref)]
    (dosync
      (alter *finished-ns-loads* (fn [_] (ensure loaded-libs-ref))))))


(defn- sync-loaded-libs
  "Watcher that removes namespaces from the set of the given reference that are not listed in *finished-ns-loads*."
  [key ref old-value new-value]
  (let [new-keys (set/difference new-value old-value)]
    (when (seq new-keys)
      (dosync
        (when-let [inconsistent-keys (seq (set/difference new-keys (ensure *finished-ns-loads*)))]
          (alter ref #(reduce disj % inconsistent-keys)))))))


(defn enabled?
  "Is transactional loading enabled?"
  []
  (deref enabled-ref))


(defn enable
  "Enables transactional loading of Clojure namespaces.
  The functions clojure.core/load-one and clojure.core/load-all are replaced via hooks.
  Returns true if txload was not enabled before."
  []
  (dosync
    (let [active? (ensure enabled-ref)]
      (when-not active?
        (initialize)
        (h/add-hook #'clojure.core/load-one ::txload #'wrapped-load-one)
        (h/add-hook #'clojure.core/load-all ::txload #'wrapped-load-all)
        (h/add-hook #'clojure.core/load-lib ::txload #'wrapped-load-lib)
        (alter enabled-ref (constantly true)))
      ; true ... if not enabled before, false ... otherwise
      (not active?))))


(defn disable
  "Disables transactional loading of Clojure namespaces.
  The watcher and all hooks are removed.
  Returns true if txload was enabled before."
  []
  (dosync
    (let [active? (ensure enabled-ref)]
      (when active?
        (h/remove-hook #'clojure.core/load-one ::txload)
        (h/remove-hook #'clojure.core/load-all ::txload)
        (h/remove-hook #'clojure.core/load-lib ::txload)
        (alter enabled-ref (constantly false)))
      active?)))
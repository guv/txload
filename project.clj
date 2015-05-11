(defproject txload "0.1.1"
  :description "Transparent transactional loading of Clojure namespaces."
  :url "https://github.com/guv/txload"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [robert/hooke "1.3.0"]]
  
  :profiles {:dev {:dependencies [[clj-debug "0.7.5"]]}})

(defproject fourtytoo/amethyst "0.1.0-SNAPSHOT"
  :description "A scheduler micro-service based on Qartz"
  :dependencies [[org.clojure/clojure "1.10.2"]
                 [cprop "0.1.17"]
                 [camel-snake-kebab "0.4.0"]
                 [spootnik/unilog "0.7.27"]
                 [org.clojure/tools.logging "1.1.0"]
                 [metosin/ring-swagger-ui "3.36.0"]
                 [metosin/ring-swagger "0.26.2"]
                 [ring "1.8.2"]
                 [metosin/jsonista "0.3.0"] ; override muuntaja's
                 [metosin/compojure-api "2.0.0-alpha30"]
                 ;; [metosin/spec-tools "0.10.5"]
                 ;; [metosin/ring-swagger-ui "3.36.0"]
                 ;; [metosin/ring-swagger "0.26.2"]
                 ;; [org.quartz-scheduler/quartz "2.2.3"]
                 [clojurewerkz/quartzite "2.1.0"]
                 [com.draines/postal "2.0.4"]]
  :ring {:handler amethyst.handler/app}
  :main         amethyst.server
  :aot          [amethyst.server]
  :uberjar-name "amethyst.jar"
  :profiles {:dev {:dependencies [[javax.servlet/javax.servlet-api "3.1.0"]]
                   :plugins [[lein-ring "0.12.5"]]
                   :resource-paths ["dev-resources"]}})

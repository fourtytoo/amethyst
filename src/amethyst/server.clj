(ns amethyst.server
  (:require [amethyst.handler :refer [handler dev-handler]]
            #_[config.core :refer [env]]
            [amethyst.config :refer :all]
            [ring.adapter.jetty :refer [run-jetty]]
            [unilog.config :refer [start-logging!]]
            [clojure.tools.logging :as log]
            [amethyst.scheduler :as scheduler])
  (:gen-class))


(def default-logging-config {:level "info" :console true})

(defonce server (atom nil))

(defn stop-jetty [server]
  (.stop server))

(defn stop-server []
  (stop-jetty @server)
  (reset! server nil))

(defn start-server [handler]
  (let [port (or (conf :rest :port) 3000)]
    (reset! server (run-jetty handler {:port port :join? false}))))

(defn restart-server [& [new-handler]]
  (when @server
    (stop-server))
  (start-server (or new-handler handler)))

#_(restart-server)

(defn add-shutdown-hook [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. f)))

(defn -main [& _args]
  (start-logging! (merge default-logging-config
                         (conf :logging)))
  (doseq [[k cls] (conf :job-types)]
    (scheduler/register-job-type k cls))
  (println "supported job types:" (conf :job-types))
  (log/info "supported job types:" (conf :job-types))
  (scheduler/start)
  (start-server handler)
  (add-shutdown-hook (fn []
                       (stop-server)
                       (scheduler/shutdown))))

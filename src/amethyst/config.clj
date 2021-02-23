(ns amethyst.config
  (:require [cprop.core :refer [load-config]]))

(def configuration (load-config))

(defn conf [& path]
  (get-in configuration path))

(ns amethyst.util
  (:import [clojure.lang RT]))


(defn ensure-class [name]
  (RT/classForName name))

(defn import-class [name]
  (.importClass (the-ns *ns*)
                (ensure-class name)))

(defn update-vals [m f & args]
  (->> m
       (map (fn [[k v]]
              [k (apply f v args)]))
       (into {})))

(defn update-keys [m f & args]
  (->> m
       (map (fn [[k v]]
              [(apply f k args) v]))
       (into {})))

(ns amethyst.enum
  (:require [camel-snake-kebab.core :as csk]))

(defn invoke-static-method [class method & args]
  (clojure.lang.Reflector/invokeStaticMethod class method (into-array Object args)))

(defn enum->keyword [enum]
  (csk/->kebab-case-keyword (.name enum)))

(defn enum->map [klass]
  (->> (invoke-static-method klass "values")
       seq
       (map (juxt enum->keyword
                  identity))
       (into {})))

(defn enum-keywords [klass]
  (keys (enum->map klass)))

;; We assume a kebab case syntax for enum values.  See also
;; `clj-grpc.core/from-grpc`
(defn keyword->enum [enum-type keyword]
  (let [name (csk/->kebab-case-string keyword)]
    (or (->> (invoke-static-method enum-type "values")
             (filter (fn [ev]
                       (= name (csk/->kebab-case-string (.name ev)))))
             first)
        (throw (ex-info "unknown keyword for enum type" {:type enum-type :keyword keyword
                                                         :legal-values (enum-keywords enum-type)})))))

(defn int->enum [enum-type x]
  (invoke-static-method enum-type "forNumber" x))

(defn as-enum [enum-type x]
  (cond (keyword? x) (keyword->enum enum-type x)
        (number? x) (int->enum enum-type x)
        :else x))

(defn enum->int [enum-type x]
  (cond (number? x) x
        (keyword? x) (.getNumber (keyword->enum enum-type x))
        :else (.getNumber x)))

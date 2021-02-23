(ns amethyst.mail
  (:require [postal.core :as post]
            [amethyst.config :refer :all]
            [clojure.tools.logging :as log]))

(defn mail [to subject body]
  (post/send-message (merge {:host "localhost"}
                            (conf :smtp))
                     (merge {:to to
                             :from (str "Amethyst <amethyst@localhost>")}
                            (conf :email)
                            {:subject subject
                             :body body})))

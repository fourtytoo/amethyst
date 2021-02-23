(ns amethyst.handler
  (:require #_[compojure.core :refer [ANY GET PUT POST DELETE defroutes context]]
            [compojure.route :refer [resources not-found]]
            [compojure.api.sweet :refer [GET PUT POST DELETE api defroutes context routes]]
            [compojure.api.exception :as ex]
            [ring.util.response :as r]
            [ring.middleware.reload :refer [wrap-reload]]
            [muuntaja.middleware :as mw]
            [cheshire.core :as json]
            [cheshire.generate :as jsongen]
            [amethyst.scheduler :as sched]
            [amethyst.schema :as schema]
            [amethyst.config :refer :all]
            [clojure.tools.logging :as log]
            [clojure.stacktrace]))


(jsongen/add-encoder java.time.LocalDateTime jsongen/encode-str)
(jsongen/add-encoder java.time.LocalDate jsongen/encode-str)
(jsongen/add-encoder java.time.LocalTime jsongen/encode-str)
(jsongen/add-encoder java.lang.Class #(jsongen/write-string %2 (.getName %1)))

(comment
  (extend-protocol cheshire.generate/JSONable
    java.time.LocalDateTime
    (to-json [dt gen]
      (cheshire.generate/write-string gen (str dt)))

    java.time.LocalDate
    (to-json [dt gen]
      (cheshire.generate/write-string gen (str dt)))

    java.time.LocalTime
    (to-json [dt gen]
      (cheshire.generate/write-string gen (str dt)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn scheduler-status-get []
  (-> (sched/get-status)
      r/response))

(defn scheduler-state-put [state]
  (case state
    :shutdown (sched/shutdown)
    :standby (sched/standby)
    :start (sched/start))
  (r/response {:status :succeed}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn list-calendars []
  (r/response {:status :succeed
               :calendars (sched/get-calendar-names)}))

(defn calendar-get [name]
  (let [calendar (sched/get-calendar name)]
    (if calendar
      (r/response {:status :succeed
                   :name name
                   :calendar calendar})
      (r/not-found {:status :fail
                    :type :not-found
                    :name name
                    :error "calendar doesn't exist"}))))

(defn calendar-post [name calendar]
  (sched/add-calendar name calendar)
  (r/created (str "/api/calendars/" name)
             {:status :succeed
              :name name}))

(defn calendar-delete [name]
  (if (sched/get-calendar name)
    (do
      (sched/delete-calendar name)
      (r/response {:status :succeed
                   :name name}))
    (r/not-found {:status :fail
                  :type :not-found
                  :name name
                  :error "calendar doesn't exist"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn list-triggers []
  (r/response {:status :succeed
               :triggers (sched/get-all-trigger-ids)}))

(defn trigger-get [group name]
  (let [tr (sched/get-trigger name group)]
    (if tr
      (r/response {:status :succeed
                   :name name
                   :group group
                   :trigger tr})
      (r/not-found {:status :fail
                    :type :not-found
                    :name name
                    :group group
                    :error "trigger doesn't exist"}))))

(defn trigger-delete [group name]
  (if (sched/get-trigger name group)
    (do
      (sched/unschedule-job name group)
      (r/response {:status :succeed
                   :name name
                   :group group}))
    (r/not-found {:status :fail
                  :type :not-found
                  :name name
                  :group group
                  :error "trigger doesn't exist"})))

(defn trigger-state-put [group name state]
  (if (sched/get-trigger name group)
    (do
      (case state
        :pause (sched/pause-trigger name group)
        :resume (sched/resume-trigger name group))
      (r/response {:status :succeed}))
    (r/not-found {:status :fail
                  :type :not-found
                  :name name
                  :group group
                  :error "trigger doesn't exist"})))

(defn trigger-post [trigger group name]
  (let [job (sched/get-job name group)]
    (if job
      (let [[group name] (sched/add-trigger trigger group name)]
        (r/created (str "/api/triggers/" group "/" name)
                   {:status :succeed
                    :name name
                    :group group}))
      (r/not-found {:status :fail
                    :type :not-found
                    :name name
                    :group group
                    :error "job doesn't exist; triggers can be added only to existing jobs"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn list-jobs []
  (r/response {:status :succeed
               :jobs (sched/list-all-job-ids)}))

(defn job-get [group name]
  (let [job (sched/get-job name group)]
    (if job
      (r/response {:status :succeed
                   :job job})
      (r/not-found {:status :fail
                    :type :not-found
                    :name name
                    :group group
                    :error "job doesn't exist"}))))

(defn job-post [job]
  (let [[group name] (sched/add-job job)]
    (r/created (str "/api/jobs/" group "/" name)
               {:status :succeed
                :name name
                :group group})))

(defn job-delete [group name]
  (if (sched/get-job name group)
    (do
      (sched/delete-job name group)
      (r/response {:status :succeed
                   :name name
                   :group group}))
    (r/not-found {:status :fail
                  :type :not-found
                  :name name
                  :group group
                  :error "job doesn't exist"})))

(defn job-state-put [group name state]
  (if (sched/get-job name group)
    (do
      (case state
      :pause (sched/pause-job name group)
      :resume (sched/resume-job name group))
      (r/response {:status :succeed}))
    (r/not-found {:status :fail
                  :type :not-found
                  :name name
                  :group group
                  :error "job doesn't exist"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-error-handler
  (ex/with-logging
    (fn [e data req]
      (ring.util.http-response/internal-server-error
       (merge {:status :fail
               :type :exception
               :class (.getName (.getClass e))
               :throwable (with-out-str (clojure.stacktrace/print-throwable e))
               :data data}
              (when-not (conf :rest :hide-stack-trace)
                {:stack (with-out-str (clojure.stacktrace/print-cause-trace e))}))))))

(def api-description
  "This API wraps the Eddy Neu Scheduler.
Jobs can be instantiated together with their schedules (triggers).
Time values are always Java instants, not local times. Calendars are
not supported, yet.

Email job example:
```clojure
{:type :email
 :description \"Send me a reminder\"
 :data {:to \"me@localhost\"
        :subject \"Reminder\"
        :body \"Remember something.\"}
 :triggers [{:type :cron
             :expression \"0 0 */2 * * ? *\"}]}
```

Same as before but only once (not repeating trigger):
```clojure
{:type :email
 :description \"Send me a reminder\"
 :data {:to \"me@localhost\"
        :subject \"Reminder\"
        :body \"Remember something.\"}
 :triggers [{:type :simple :start \"2021-02-13T13:48:20.662Z\"}]}
```

Log job example:
```clojure
{:type :log
 :description \"Log something once in a while\"
 :data {:level :info
        :message \"Something\"}
 :triggers [{:type :cron
             :expression \"0 */5 * * * ? *\"}]}
```

Kafka message job example:
```clojure
{:type :kafka-message
 :description \"Send seasonal greetings\"
 :data {:queue \"one-of-those\"
        :encoding \"json\"
        :message {:wish \"Merry Christmas!\"}}
 :triggers [{:type :cron
             :expression \"0 0 0 25 12 ? *\"}]}
```")

(def jobs-list-description
  "This get will return a list of ids of all jobs currently stored in
the scheduler.  Whether they are actually active or not.  The result
will have the jobs collected in job group.")

(def job-post-description
  "This endpoint adds a job to the scheduler.  The job needs to be
specified in the body of the POST.  You don't need (but you can)
specify a job name or group; the default group will be used and a
unique name will be generated automatically for you.  This operation
returns the name and group of the new job.

Example EDN request body:
```clojure
{:type :email
 :description \"Send me a reminder\"
 :data {:to \"me@localhost\"
        :subject \"Reminder\"
        :body \"Remember something.\"}
 :triggers [{:type :cron
             :expression \"0 0 */2 * * ? *\"}]}
```")

(defroutes api-job-routes
  (GET "/jobs" []
    :summary "List job ids"
    :description jobs-list-description
    :return schema/job-list-result
    (list-jobs))
  (GET "/jobs/:group/:name" [group name]
    :path-params [group :- schema/id name :- schema/id]
    :summary "Fetch a job"
    :description "Fetch a specific job currently managed by the
      scheduler.  You need to specify a job name and group in the
      path.  For a list of jobs use the GET /jobs endpoint without
      name or group."
    :return schema/job-get-result
    (job-get group name))
  (DELETE "/jobs/:group/:name" [group name]
    :path-params [group :- schema/id name :- schema/id]
    :summary "Delete a job"
    :description "Delete a specific job from the scheduler.  You
       need to specify the job name and group in the path."
    (job-delete group name))
  (POST "/jobs" []
    :summary "Add a job"
    :description job-post-description
    :body [job schema/Job]
    :return schema/job-post-result
    (job-post job))
  (PUT "/jobs/state/:group/:name/:state" [group name state]
    :path-params [group :- schema/id name :- schema/id state :- schema/job-state]
    :summary "Change a job's state"
    :description "Change the state of a job.  A job can be paused (all
      its triggers are paused) and, later, resumed (all its triggers
      are resumed)."
    ;; :return schema/job-list-result
    (job-state-put group name (keyword state))))

(defroutes api-calendar-routes
  (GET "/calendars" []
    :summary "List calendars"
    :description "Return a list of names of calendars currently loaded in the scheduler."
    ;; :return schema/job-list-result
    (list-calendars))
  (GET "/calendars/:name" [name]
    :summary "Fetch a calendar"
    :description "Fetch a specific calendar from the scheduler."
    ;; :return schema/job-list-result
    (calendar-get name))
  (DELETE "/calendars/:name" [name]
    :summary "Delete a calendar"
    :description "Remove a specific calendar from the scheduler."
    ;; :return schema/job-list-result
    (calendar-delete name))
  (POST "/calendars/:name" [name]
    :summary "Add a calendar"
    :description "TODO!! Store a calendar in the scheduler." ; -wcp29/01/21
    :body [calendar schema/Calendar]
    ;; :return schema/job-list-result
    (calendar-post name calendar)))

(defroutes api-trigger-routes
  (GET "/triggers" []
    :summary "List triggers"
    :description "Return a list of ids of triggers currently loaded in
      the scheduler."
    ;; :return schema/job-list-result
    (list-triggers))
  (GET "/triggers/:group/:name" [group name]
    :summary "Fetch a trigger"
    :description "Fetch a specific trigger from the scheduler."
    ;; :return schema/job-list-result
    (trigger-get group name))
  (DELETE "/triggers/:gorup/:name" [group name]
    :path-params [group :- schema/id name :- schema/id]
    :summary "Delete a trigger"
    :description "Remove a specific trigger from the scheduler.
       That means that the associated job will not be triggered by it.
       If a job is orphaned of all its triggers, it will never be
       scheduled."
    ;; :return schema/job-list-result
    (trigger-delete group name))
  (POST "/triggers/:jgroup/:jname" [jgroup jname]
    :path-params [jgroup :- schema/id jname :- schema/id]
    :summary "Add a trigger"
    :description "Store a trigger in the scheduler.  The group and
      name parameters refer to the job the trigger should schedule.
      The trigger will be added to the job; the triggers that are
      already associated to the job won't be affected.  To replace a
      job's triggers, delete them first."
    :body [trigger schema/Trigger]
    ;; :return schema/job-list-result
    (trigger-post trigger jgroup jname))
  (PUT "/triggers/state/:group/:name/:state" [group name state]
    :path-params [group :- schema/id name :- schema/id state :- schema/trigger-state]
    :summary "Change a trigger's state"
    :description "Change state of a trigger.  Triggers can be
       paused or resumed."
    ;; :return schema/job-list-result
    (trigger-state-put group name (keyword state))))

(defroutes api-routes
  (api                                  ; mw/wrap-format
   {:coercion :schema                   ;doesn't work? -wcp27/01/21
    ;; :formats [:json-kw :edn :transit-msgpack :transit-json]
    :exceptions {:handlers
                 {::ex/default default-error-handler}}
    :swagger
    {:ui "/docs"
     :spec "/swagger.json"
     ;; :options {:ui {:jsonEditor false} :spec {}}
     :data {:info {:title "Eddy Scheduler API"
                   :description api-description}
            ;; :consumes ["application/json" "application/edn"]
            ;;:produces ["application/json" "application/edn"]
            }}}
   (context "/api" []
     (GET "/scheduler" []
       :summary "Scheduler status"
       :description "Return the current status of the scheduler."
       (scheduler-status-get))
     (PUT "/scheduler/state/:state" [state]
       :path-params [state :- schema/scheduler-state]
       :summary "Change scheduler's state"
       :description "Change running state of the scheduler.  The
       scheduler can be shutdown (and restarted) or put in
       standby (and resumed)."
       (scheduler-state-put (keyword state)))
     api-job-routes
     api-calendar-routes
     api-trigger-routes)))

(defroutes all-routes
  (GET "/" []
    (r/resource-response "index.html" {:root "public"}))
  api-routes
  (resources "/")
  (not-found "Page not found"))

(def dev-handler
  (-> #'all-routes wrap-reload))

(def handler all-routes)

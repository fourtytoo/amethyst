(ns amethyst.scheduler
  (:require [clojurewerkz.quartzite.scheduler :as qs]
            [clojurewerkz.quartzite.jobs :as qj]
            [clojurewerkz.quartzite.conversion :as qc]
            [clojurewerkz.quartzite.triggers :as qt]
            [clojurewerkz.quartzite.schedule.simple :as qss]
            [clojurewerkz.quartzite.schedule.cron :as qsc]
            [clojurewerkz.quartzite.schedule.daily-interval :as qsdti]
            [clojurewerkz.quartzite.schedule.calendar-interval :as qsci]
            [clojurewerkz.quartzite.schedule.simple :as qss]
            [amethyst.enum :as enum]
            [amethyst.mail :as mail]
            [amethyst.util :refer :all]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn])
  (:import [org.quartz Job DateBuilder$IntervalUnit Trigger$TriggerState JobDataMap JobExecutionContext]
           [java.util Calendar Date]
           [java.time LocalDateTime Instant ZoneOffset]
           [clojure.lang IPersistentMap]))


(extend-protocol clojurewerkz.quartzite.conversion/JobDataMapConversion
  JobDataMap
  (from-job-data [^JobDataMap input]
    (update-keys input keyword)))

(defmacro defjob [name [data] & body]
  `(qj/defjob ~name [ctx#]
     (let [~data (qc/from-job-data ctx#)]
       ~@body)))

(defjob EMailJob [{:keys [to subject body]}]
  (log/info "Send email" to subject body)
  (mail/mail to subject body))

(defjob LogJob [{:keys [level message]}]
  (log/log level message))

(defjob PrintJob [data]
  (prn 'PrintJob (type data) data))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-scheduler-listener [f]
  (proxy [org.quartz.SchedulerListener] []
    (jobAdded [job-detail]
      (f :jobAdded job-detail))
    (jobDeleted [job-key]
      (f :jobDeleted job-key))
    (jobPaused [job-key]
      (f :jobPaused job-key))
    (jobResumed [job-key]
      (f :jobResumed job-key))
    (jobScheduled [trigger]
      (f :jobScheduled trigger))
    (jobsPaused [group-name]
      (f :jobsPaused group-name))
    (jobsResumed [group-name]
      (f :jobsResumed group-name))
    (jobUnscheduled [trigger-key]
      (f :jobUnscheduled trigger-key))
    (schedulerError [message exception]
      (f :schedulerError message exception))
    (schedulerInStandbyMode []
      (f :schedulerInStandbyMode ))
    (schedulerShutdown []
      (f :schedulerShutdown ))
    (schedulerShuttingdown []
      (f :schedulerShuttingdown))
    (schedulerStarted []
      (f :schedulerStarted ))
    (schedulingDataCleared []
      (f :schedulingDataCleared ))
    (triggerFinalized [trigger]
      (f :triggerFinalized trigger))
    (triggerPaused [trigger-key]
      (f :triggerPaused trigger-key))
    (triggerResumed [trigger-key]
      (f :triggerResumed trigger-key))
    (triggersPaused [group-name]
      (f :triggersPaused group-name))
    (triggersResumed [group-name]
      (f :triggersResumed group-name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce scheduler (atom nil))

(def scheduler-logging (atom false))

#_(reset! scheduler-logging true)

(defn log-scheduler-events [event-type & args]
  (when @scheduler-logging
    (log/info "Scheduler Event" event-type args)))

(defn start []
  (when-not @scheduler
    (reset! scheduler (qs/initialize))
    (qs/add-scheduler-listener @scheduler (make-scheduler-listener log-scheduler-events))
    (qs/start @scheduler)))

(defn shutdown [& [wait-for-jobs?]]
  (when @scheduler
    (qs/shutdown @scheduler (boolean wait-for-jobs?))
    ;; once shutdown, a scheduler cannot be restarted
    (reset! scheduler nil)))

(defn standby []
  (qs/standby @scheduler))

(defn running? []
  (and @scheduler
       (not (qs/standby? @scheduler))))

(def job-types (atom #{}))
(def key->job-type (atom {}))

(defn register-job-type [k cls]
  (swap! job-types conj cls)
  (swap! key->job-type assoc k cls))

(defn job-type [job]
  ;; this is necessary when we reload the code and new classes are
  ;; defined (thus, orphaning the jobs already in the scheduler)
  (-> (.getJobClass job)
      .getName))

(defn job-detail->map [j]
  {:name (.getName j)
   :group (.getGroup j)
   :type (job-type j)
   :description (.getDescription j)
   :data (qc/from-job-data (.getJobDataMap j))})

(def interval-units (set (enum/enum-keywords DateBuilder$IntervalUnit)))

(defn as-interval-unit [u]
  (enum/as-enum DateBuilder$IntervalUnit u))

(defn interval-unit->keyword [u]
  (enum/enum->keyword u))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti trigger-map->schedule :type)

(defmethod trigger-map->schedule :cron
  [m]
  (qsc/cron-schedule (:expression m)))

(defmethod trigger-map->schedule :calendar
  [m]
  (let [{:keys [unit interval]} m]
    (qsci/schedule
     (.withInterval interval (as-interval-unit unit)))))

(def weekday->int
  {:sunday Calendar/SUNDAY
   :monday Calendar/MONDAY
   :tuesday Calendar/TUESDAY
   :wednsday Calendar/WEDNESDAY
   :thursday Calendar/THURSDAY
   :friday Calendar/FRIDAY
   :saturday Calendar/SATURDAY})

(def int->weekday (zipmap (vals weekday->int) (keys weekday->int)))

(defmethod trigger-map->schedule :daily-time
  [m]
  (let [{:keys [unit interval repeat week-days start-daily end-daily]} m]
    (qsdti/schedule
     (.withInterval interval (as-interval-unit unit))
     (cond->
         repeat (qsdti/with-repeat-count repeat)
         (empty? week-days) (qsdti/every-day)
         week-days (qsdti/days-of-the-week (set (map weekday->int week-days)))
         start-daily (qsdti/starting-daily-at (qsdti/time-of-day start-daily))
         end-daily (qsdti/ending-daily-at (qsdti/time-of-day end-daily))))))

(defmethod trigger-map->schedule :simple
  [m]
  (let [{:keys [ms seconds minutes hours days repeat]} m]
    (qss/schedule
     (cond->
         ms (qss/with-interval-in-milliseconds ms)
         seconds (qss/with-interval-in-seconds seconds)
         minutes (qss/with-interval-in-minutes minutes)
         hours (qss/with-interval-in-hours hours)
         days (qss/with-interval-in-days days)
         (= repeat :forever) (qss/repeat-forever)
         (integer? repeat) (qss/with-repeat-count repeat)))))

(defmethod trigger-map->schedule :default
  [m]
  (throw
   (ex-info "unsupported trigger type"
            {:type (:type m) :conf m})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FIXME: currently we support only cron triggers -wcp19/01/21

(defmulti trigger->map class)

(defmethod trigger->map org.quartz.CronTrigger
  [trigger]
  {:type :cron
   :expression (.getCronExpression trigger)})

(defmethod trigger->map org.quartz.CalendarIntervalTrigger
  [trigger]
  {:type :calendar
   :interval (.getRepeatInterval trigger)
   :unit (interval-unit->keyword (.getRepeatIntervalUnit trigger))})

(defmethod trigger->map org.quartz.DailyTimeIntervalTrigger
  [trigger]
  {:type :daily-time
   :week-days (mapv int->weekday (.getDaysOfWeek trigger))
   :start-daily (.getStartTimeOfDay trigger)
   :end-daily (.getEndTimeOfDay trigger)
   :repeat (.getRepeatCount trigger)
   :interval (.getRepeatInterval trigger)
   :unit (interval-unit->keyword (.getRepeatIntervalUnit trigger))})

(defmethod trigger->map org.quartz.SimpleTrigger
  [trigger]
  {:type :simple
   :ms (.getRepeatInterval trigger)
   :repeat (.getRepeatCount trigger)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-trigger-state [t]
  (->> (.getKey t)
       (.getTriggerState @scheduler)
       enum/enum->keyword))

(defn trigger-to-map [trigger]
  (let [k (.getKey trigger)]
    (merge {:name (.getName k)
            :group (.getGroup k)
            :state (get-trigger-state trigger)
            :priority (.getPriority trigger)
            :description (.getDescription trigger)
            :start (.getStartTime trigger)
            :end (.getEndTime trigger)
            :previous-fire-time (.getPreviousFireTime trigger)
            :next-fire-time (.getNextFireTime trigger)}
           (trigger->map trigger))))

(defn- get-job-by-key [k]
  (when-let [job (qs/get-job @scheduler k)]
    (-> (job-detail->map job)
        (assoc :triggers (map trigger-to-map (qs/get-triggers-of-job @scheduler k))))))

(defn get-job [name & [group]]
  (get-job-by-key (if group
                    (qj/key name group)
                    (qj/key name))))

(defn list-all-job-ids []
  (->> (qs/get-job-keys @scheduler nil)
       (map (fn [k]
              {:group (.getGroup k)
               :name (.getName k)}))
       (group-by :group)
       (map (fn [[g ks]]
              [g (map :name ks)]))
       (into {})))

(defn list-all-jobs
  "Return the collection of all jobs currently in the scheduler."
  []
  (->> (qs/get-job-keys @scheduler nil)
       (map get-job-by-key)))

#_(list-all-jobs)

(defmacro ignore-errors [& forms]
  `(try ~@forms
        (catch Exception _# nil)))

(defn string->date [s]
  (Date/from (or (ignore-errors (Instant/parse s))
                 (ignore-errors
                  (.toInstant
                   (.atZone (LocalDateTime/parse s)
                            (ZoneOffset/systemDefault))))
                 (throw (ex-info "malformed date/time string" {:string s})))))

#_((juxt type identity)(string->date "2021-02-16T14:31"))

(extend-protocol clojurewerkz.quartzite.conversion/DateConversion
  String
  (to-date [input]
    (string->date input)))

(defn tkey [& [name group]]
  (cond (and name group) (qt/key name group)
        name (qt/key name)
        :else (qt/key)))

(defn jkey [& [name group]]
  (cond (and name group) (qj/key name group)
        name (qj/key name)
        :else (qj/key)))

(defn make-trigger [schedule-conf job]
  (let [{:keys [name group start end description priority]} schedule-conf]
    (qt/build
     (qt/with-identity (tkey name group))
     (qt/with-schedule (trigger-map->schedule schedule-conf))
     (cond->
         priority (qt/with-priority priority)
         description (qt/with-description description)
         start (qt/start-at start)
         end (qt/end-at end)
         (nil? start) (qt/start-now)
         job (qt/for-job job)))))

(defn make-job [type & {:keys [name group description data durable]}]
  (let [type (or (@key->job-type type)
                 type)]
    (assert (get job-types type) "unregistered job type")
    (qj/build
     (qj/of-type (ensure-class type))
     (qj/with-identity (jkey name group))
     (cond->
         data (qj/using-job-data data)
         description (qj/with-description description)
         durable (qj/store-durably)))))

(defn add-job [job]
  (log/debug "add-job" (pr-str job))
  (let [{:keys [triggers type name group description data]} job
        job (make-job type :name name :group group
                      :data data
                      :description description :durable true)]
    (qs/add-job @scheduler job)
    (doseq [t triggers]
      (qs/add-trigger @scheduler (make-trigger t job)))
    [(.getGroup job)
     (.getName job)]))

(defn pause-job [name & [group]]
  (qs/pause-job @scheduler (jkey name group)))

(defn resume-job [name & [group]]
  (qs/resume-job @scheduler (jkey name group)))

(defn delete-job [name & group]
  (qs/delete-job @scheduler
                 (jkey name group)))

#_(delete-job "6da64b5bd2ee-93ce6281-5b5a-4752-b3db-d9a35cd0f4d4")

(defn delete-all-jobs
  "Empty the scheduler of all its jobs. If `group` is provided, remove
  only the jobs belonging to that group."
  [& [group]]
  (->> (qs/get-job-keys @scheduler group)
       (run! (partial qs/delete-job @scheduler))))

(defn get-status []
  (if @scheduler
    (-> (.getMetaData @scheduler)
        bean
        (dissoc :summary)
        (assoc :job-groups (.getJobGroupNames @scheduler)
               :trigger-groups (.getTriggerGroupNames @scheduler)
               :context (.getContext @scheduler)))
    {:started false
     :shutdown true}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-calendar-names []
  (.getCalendarNames @scheduler))

(defn get-calendar [name]
  (.getCalendar @scheduler name))

(defn add-calendar [name calendar & {:keys [replace? update-triggers?]}]
  ;; TODO: calendar has to be converted from map to POJO -wcp28/01/21
  (.addCalendar @scheduler name calendar
                (boolean replace?) (boolean update-triggers?)))

(defn delete-calendar [name]
  (.deleteCalendar @scheduler name))

(defn delete-calendar [name]
  (.deleteCalendar @scheduler name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-all-trigger-ids []
  (->> (qs/get-trigger-keys @scheduler nil)
       (map (fn [k]
              {:group (.getGroup k)
               :name (.getName k)}))
       (group-by :group)
       (map (fn [[g ks]]
              [g (map :name ks)]))
       (into {})))

(defn- get-trigger-by-key [k]
  (when-let [tr (qs/get-trigger @scheduler k)]
    (-> (trigger-to-map tr)
        (assoc :job (str (.getJobKey tr))))))

(defn get-trigger [name & [group]]
  (get-trigger-by-key (tkey name group)))

(defn unschedule-job
  "Delete the trigger identified by `name` and `group`."
  [name & [group]]
  (.unscheduleJob @scheduler (tkey name group)))

(defn pause-trigger [name & [group]]
  (qs/pause-trigger @scheduler (tkey name group)))

(defn resume-trigger [name & [group]]
  (qs/resume-trigger @scheduler (tkey name group)))

(defn add-trigger [trigger job-group job-name]
  (let [trigger' (make-trigger trigger (jkey job-name job-group))]
    (qs/add-trigger @scheduler trigger')
    [(.getGroup trigger')
     (.getName trigger')]))


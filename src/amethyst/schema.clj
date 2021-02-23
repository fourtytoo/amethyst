(ns amethyst.schema
  (:require [schema.core :as schema]
            [schema.experimental.abstract-map :as abstract-map]
            [amethyst.enum :as enum]
            [amethyst.scheduler :as sched]))


(def TriggerCommon
  {(schema/optional-key :start) schema/Str
   (schema/optional-key :end) schema/Str})

(schema/defschema Trigger
  (abstract-map/abstract-map-schema
   :type
   TriggerCommon))

(abstract-map/extend-schema
 CronTrigger Trigger [:cron]
 {:expression schema/Str})

(abstract-map/extend-schema
 CalendarTrigger Trigger [:calendar]
 {:unit (apply schema/enum sched/interval-units)
  :interval schema/Int})

(abstract-map/extend-schema
 DailyTrigger Trigger [:daily-time]
 {:unit (apply schema/enum sched/interval-units)
  :interval schema/Int
  (schema/optional-key :repeat) schema/Int
  (schema/optional-key :week-days) [schema/Keyword]
  (schema/optional-key :start-daily) schema/Str
  (schema/optional-key :end-daily) schema/Str})

(abstract-map/extend-schema
 SimpleTrigger Trigger [:simple]
 {(schema/optional-key :ms) schema/Int
  (schema/optional-key :seconds) schema/Int
  (schema/optional-key :minutes) schema/Int
  (schema/optional-key :hours) schema/Int
  (schema/optional-key :days) schema/Int
  (schema/optional-key :repeat) #{schema/Int}})

(comment
  (schema/validate SimpleTrigger {:type :simple :name "melvin" :foo 123})
  (schema/validate Trigger {:type :cron :expression "something"})
  (schema/validate Trigger {:type :simple :name "roofer" :foo 123}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def Job
  {(schema/optional-key :name) schema/Str
   (schema/optional-key :group) schema/Str
   (schema/optional-key :description) schema/Str
   :type schema/Str
   :data schema/Any
   :triggers [Trigger]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(schema/defschema job-get-result
  {:status schema/Keyword
   :job Job})

(def id schema/Str)

(schema/defschema job-post-result
  {:status schema/Keyword
   :name id
   :group id})

(schema/defschema job-list-result
  {:status schema/Keyword
   :jobs [{id [id]}]})

(schema/defschema scheduler-state
  (schema/enum :shutdown :standby :start))

(schema/defschema Calendar
  schema/Any)

(schema/defschema job-state
  (schema/enum :pause :resume))

(schema/defschema trigger-state
  (schema/enum :pause :resume))

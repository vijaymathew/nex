(ns nex.types.datetime)

(do
     (import '[java.time Instant LocalDateTime ZoneOffset ZonedDateTime])
     (import '[java.time.format DateTimeFormatter]))

(defn datetime-now []
     (.toEpochMilli (Instant/now)))

(defn datetime-from-epoch-millis [ms]
     (long ms))

(defn datetime-parse-iso [text]
     (try
       (.toEpochMilli (Instant/parse (str text)))
       (catch Exception _
         (let [ldt (LocalDateTime/parse (str text))]
           (.toEpochMilli (.toInstant (.atZone ldt ZoneOffset/UTC)))))))

(defn datetime-make [year month day hour minute second]
     (-> (LocalDateTime/of (int year) (int month) (int day) (int hour) (int minute) (int second))
         (.atZone ZoneOffset/UTC)
         .toInstant
         .toEpochMilli))

(defn- zdt-utc [epoch-ms]
     (ZonedDateTime/ofInstant (Instant/ofEpochMilli (long epoch-ms)) ZoneOffset/UTC))

(defn datetime-year [epoch-ms] (.getYear (zdt-utc epoch-ms)))
(defn datetime-month [epoch-ms] (.getMonthValue (zdt-utc epoch-ms)))
(defn datetime-day [epoch-ms] (.getDayOfMonth (zdt-utc epoch-ms)))
(defn datetime-weekday [epoch-ms] (.getValue (.getDayOfWeek (zdt-utc epoch-ms))))
(defn datetime-day-of-year [epoch-ms] (.getDayOfYear (zdt-utc epoch-ms)))
(defn datetime-hour [epoch-ms] (.getHour (zdt-utc epoch-ms)))
(defn datetime-minute [epoch-ms] (.getMinute (zdt-utc epoch-ms)))
(defn datetime-second [epoch-ms] (.getSecond (zdt-utc epoch-ms)))
(defn datetime-epoch-millis [epoch-ms] (long epoch-ms))
(defn datetime-add-millis [epoch-ms delta-ms] (+ (long epoch-ms) (long delta-ms)))
(defn datetime-diff-millis [left-ms right-ms] (- (long left-ms) (long right-ms)))
(defn datetime-truncate-to-day [epoch-ms]
     (-> (zdt-utc epoch-ms)
         (.withHour 0)
         (.withMinute 0)
         (.withSecond 0)
         (.withNano 0)
         .toInstant
         .toEpochMilli))
(defn datetime-truncate-to-hour [epoch-ms]
     (-> (zdt-utc epoch-ms)
         (.withMinute 0)
         (.withSecond 0)
         (.withNano 0)
         .toInstant
         .toEpochMilli))
(defn datetime-format-iso [epoch-ms]
     (.format DateTimeFormatter/ISO_INSTANT (Instant/ofEpochMilli (long epoch-ms))))

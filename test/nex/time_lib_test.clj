(ns nex.time-lib-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.repl :as repl]))

(deftest time-library-typechecked-date-time-api-test
  (testing "Date_Time API typechecks through the REPL path used by docs examples"
    (binding [repl/*type-checking-enabled* (atom true)]
      (let [ctx (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx "intern time/Duration")
                     (repl/eval-code ctx "intern time/Date_Time")
                     (repl/eval-code ctx "let started_at: Date_Time := create Date_Time.now()")
                     (repl/eval-code ctx "let next_run: Date_Time := started_at.add(create Duration.minutes(15))")
                     (repl/eval-code ctx "let weekly_cutoff: Date_Time := started_at.add(create Duration.weeks(1))")
                     (repl/eval-code ctx "print(started_at.weekday())")
                     (repl/eval-code ctx "print(started_at.day_of_year())")
                     (repl/eval-code ctx "print(next_run.truncate_to_hour().format_iso())")
                     (repl/eval-code ctx "print(weekly_cutoff.truncate_to_day().format_iso())"))]
        (is (not (.contains output "Type checking failed")))
        (is (not (.contains output "Undefined function or method: datetime_weekday")))
        (is (not (.contains output "Undefined function or method: datetime_day_of_year")))))))

(deftest time-library-runtime-test
  (testing "Date_Time and Duration libraries work through the JVM interpreter"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern time/Duration")
                   (repl/eval-code ctx "intern time/Date_Time")
                   (repl/eval-code ctx "let d1: Duration := create Duration.minutes(5)")
                   (repl/eval-code ctx "let d2: Duration := create Duration.seconds(30)")
                   (repl/eval-code ctx "print(d1.plus(d2).total_seconds())")
                   (repl/eval-code ctx "print(create Duration.weeks(1).total_seconds())")
                   (repl/eval-code ctx "let start: Date_Time := create Date_Time.make(2026, 3, 13, 10, 30, 0)")
                   (repl/eval-code ctx "let later: Date_Time := start.add(create Duration.hours(2))")
                   (repl/eval-code ctx "print(start.year())")
                   (repl/eval-code ctx "print(start.month())")
                   (repl/eval-code ctx "print(start.month_name())")
                   (repl/eval-code ctx "print(start.day())")
                   (repl/eval-code ctx "print(start.weekday())")
                   (repl/eval-code ctx "print(start.weekday_name())")
                   (repl/eval-code ctx "print(start.day_of_year())")
                   (repl/eval-code ctx "print(later.hour())")
                   (repl/eval-code ctx "print(later.is_after(start))")
                   (repl/eval-code ctx "print(start.format_iso())")
                   (repl/eval-code ctx "print(start.truncate_to_day().format_iso())")
                   (repl/eval-code ctx "print(start.truncate_to_hour().format_iso())")
                   (repl/eval-code ctx "let parsed: Date_Time := create Date_Time.parse_iso(\"2026-03-13T10:30:00Z\")")
                   (repl/eval-code ctx "print(parsed.epoch_millis() = start.epoch_millis())")
                   (repl/eval-code ctx "print(later.difference(start).total_seconds())"))]
      (is (.contains output "330.0"))
      (is (.contains output "604800.0"))
      (is (.contains output "2026"))
      (is (.contains output "\"March\""))
      (is (.contains output "\"Friday\""))
      (is (.contains output "72"))
      (is (.contains output "12"))
      (is (.contains output "true"))
      (is (.contains output "\"2026-03-13T10:30:00Z\""))
      (is (.contains output "\"2026-03-13T00:00:00Z\""))
      (is (.contains output "7200.0")))))

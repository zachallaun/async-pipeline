(ns async-pipeline.t-core
  (:require [async-pipeline.core :refer :all]
            [midje.sweet :refer :all]
            [clojure.core.async :as async
             :refer [go chan dropping-buffer >! <! >!! alt!!
                     close! timeout put!]]))

(defn increment [in out]
  (go-try
    (loop []
      (when-recv [num (<! in)]
        (>! out (inc num))
        (recur)))))

(defn drop-every-third [in out]
  (go-try
    (loop [tick 1]
      (when-recv [msg (<! in)]
        (if (= tick 3)
          (recur 1)
          (do (>! out msg)
              (recur (inc tick))))))))

(defn error-every-third [in out]
  (go-try
    (loop [tick 1]
      (when-recv [msg (<! in)]
        (if (= tick 3)
          [:error]
          (do (>! out msg)
              (recur (inc tick))))))))

(defn throw-every-third [in out]
  (go-try
    (loop [tick 1]
      (when-recv [msg (<! in)]
        (if (= tick 3)
          (throw (Exception. :boom))
          (do (>! out msg)
              (recur (inc tick))))))))

(defn <!!
  "Like <!!, but with an optional timeout.
Why is this not in core.async, yo?"
  ([port]
     (clojure.core.async/<!! port))
  ([port timeout-ms timeout-val]
     (alt!!
       (timeout timeout-ms) ([_] timeout-val)
       port ([val] val))))

(binding [async-pipeline.core/*debug* false]

  (facts "about pipeline"

    (fact "in, out, err, and ctrl satisfy the correct protocols"
      (let [[in out err ctrl] (pipeline chan
                                increment)]
        (instance? clojure.core.async.impl.protocols.WritePort in) => true
        (instance? clojure.core.async.impl.protocols.ReadPort out) => true
        (instance? clojure.core.async.impl.protocols.ReadPort err) => true
        (instance? clojure.core.async.impl.protocols.WritePort ctrl) => true))

    (let [[in out err ctrl] (pipeline chan
                              increment)]
      (fact "values can be piped through"
        (put! in 1)
        (<!! out 100 :fail) => 2)

      (fact "closing the ctrl channel closes the pipeline (and related chans)"
        (async/close! ctrl)
        (<!! (timeout 100)) ;; give it time to close
        (put! in 1)
        (<!! out 100 :fail) => nil))

    (let [[in out err ctrl] (pipeline chan
                              increment
                              drop-every-third)]
      (fact "messages can be filtered"
        (go (dotimes [i 4] (>! in i)))
        (<!! out 100 :fail) => 1
        (<!! out 100 :fail) => 2
        (<!! out 100 :fail) => 4
        (async/close! ctrl)))

    (let [[in out err ctrl] (pipeline chan
                              increment
                              error-every-third)]
      (fact "errored processes will be restarted"
        (go (dotimes [i 3] (>! in i)))
        (<!! out 100 :fail) => 1
        (<!! out 100 :fail) => 2
        (<!! (timeout 100))
        (<!! err 100 :fail) => vector?
        (<!! out 100 :empty) => :empty
        (>!! in 4)
        (<!! out 100 :fail) => 5
        (async/close! ctrl)))

    (let [[in out err ctrl] (pipeline chan
                              increment
                              throw-every-third)]
      (fact "processes that throw exceptions will be restarted"
        (go (dotimes [i 3] (>! in i)))
        (<!! out 100 :fail) => 1
        (<!! out 100 :fail) => 2
        (<!! (timeout 100))
        (<!! err 100 :fail) => (fn [[msg ex]]
                                 (and (= :thrown msg)
                                      (instance? Exception ex)))
        (<!! out 100 :empty) => :empty
        (>!! in 4)
        (<!! out 100 :fail) => 5
        (async/close! ctrl))))

  )

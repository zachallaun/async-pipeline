# async-pipeline

A small library for creating `clojure.core.async` process pipelines.

_Inspired by [Messaging as a Programming Model](http://eventuallyconsistent.net/2013/08/12/messaging-as-a-programming-model-part-1/)._

```clj
(require '[clojure.core.async :as async]
         '[async-pipeline :refer [pipeline go-try]]
         '[clojure.core.match :refer [match]])

(defn filter-valid-credentials
  [in out]
  (go-try
    (loop []
      (when-recv [{:keys [username password] :as msg} (<! in)]
        (if (valid-credentials? username password)
          (do (>! out msg)
              (recur))
          [:error :invalid-credentials msg])))))

(defn filter-api-key-enabled
  [in out]
  (go-try
    (loop []
      (when-recv [{:keys [api-key] :as msg} (<! in)]
        (if-let [details (api-details api-key)]
          (do (>! out (assoc msg :api-details details))
              (recur))
          [:error :invalid-api-key msg])))))

(defn get-user-info
  [in out]
  (go-try
    (loop []
      (when-recv [{:keys [username api-details] :as msg} (<! in)]
        (>! out (assoc msg :user-info (user-info-for username api-details)))
        (recur)))))

(def login-pipeline
  (pipeline async/chan
    filter-valid-credentials
    filter-api-key-enabled
    get-user-info))

(let [[in out err ctrl] login-pipeline]
  (forward-login-requests in)
  (handle-login-errors err)
  (respond-with-details out)

  (async/put! in {:username "zach" :password "p@$$w04d"}))
```

(ns sidecar-nrepl.middleware
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.misc :refer [response-for]]

            [clojure.tools.nrepl.server :as nrepl.server]
            [clojure.tools.nrepl.transport :as nrepl.transport]
            [clojure.tools.nrepl.middleware :as nrepl.middleware]

            [cider.nrepl.middleware.info]
            [cider.nrepl.middleware.util.error-handling]))


(defn- run-cider-nrepl-op
  "Runs the given cider-nrepl op function.

  cider nrepl 'op functions' such as cider.nrepl.middleware.info/info-reply does not
  attach the :status field in its response map. It relies on the op-handler function
  to attach the correct status.

  This means if we just call the op-fn on its own, nrepl will think the requested operation
  has not completed and will wait until the connection times out.

  This function is here only to aid code readability."
  [op-fn msg]

  (cider.nrepl.middleware.util.error-handling/op-handler op-fn msg))


(defn wrap-sidedocs
  [handler]
  (fn [{:keys [op transport] :as msg}]
    (if (= "info" op)
      ;; Unfortunately, nrepl middlewares don't seem to return a response map that we can
      ;; directly manipulate.
      ;; So to have cider.nrepl deal with the var info request, we have to call into
      ;; the cider nrepl innards directly.
      (let [info-result (merge (run-cider-nrepl-op cider.nrepl.middleware.info/info-reply msg)
                               {:middleware :sidedocs})]
        ;; TODO Implement docstring replacement

        ;; If cider.nrepl was able to lookup the documentation successfully...
        ;; Check if we have a new docstring for the var
        ;; If so, replace the original docstring
        ;; Send the reply
        (nrepl.transport/send transport (response-for msg info-result))
        ))))

(nrepl.middleware/set-descriptor! #'wrap-sidedocs
  ;; Install the middleware ahead of the "info" handler
  {:expects #{"info"}})


(defn- start-server
  "Starts an nrepl server locally with given middlewares"
  [& middlewares]
  (nrepl.server/start-server
   :handler (apply nrepl.server/default-handler middlewares)))


(defn- send-nrepl-message
  [port msg]

  (with-open [conn (nrepl/connect :port port)]
              (-> (nrepl/client conn 1000)
                  (nrepl/message msg)
                  nrepl/combine-responses)))


(defn- send-nrepl-message-to-server
  [server msg]

  (send-nrepl-message (.getLocalPort (:ss server)) msg))


(comment
  (with-open [server (start-server)]
    (send-nrepl-message-to-server server {:op "describe"})
    )

  (with-open [server (start-server)]
    (send-nrepl-message-to-server server {:op "eval" :code "(+ 2 3)"}))

  (with-open [server (start-server #'cider.nrepl.middleware.info/wrap-info
                                   #'wrap-sidedocs
                      )]
    (time
     (send-nrepl-message-to-server server {:op "info" :symbol "str" :ns "clojure.core"})))

  )

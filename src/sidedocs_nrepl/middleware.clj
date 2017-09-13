(ns sidedocs-nrepl.middleware
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.misc :refer [response-for]]

            [clojure.tools.nrepl.server :as nrepl.server]
            [clojure.tools.nrepl.transport :as nrepl.transport]
            [clojure.tools.nrepl.middleware :as nrepl.middleware]

            [cider.nrepl.middleware.info]
            [cider.nrepl.middleware.util.error-handling]
            [clojure.java.io :as io]))


;;------------------------------------------------------------------------------
;; Locating side-loaded docstrings
;;------------------------------------------------------------------------------

(defn- get-working-dir
  []
  (System/getProperty "user.dir"))


(defn- expand-home [s]
  (if (.startsWith s "~")
    (clojure.string/replace-first s "~" (System/getProperty "user.home"))
    s))


(defn- doc-repos
  "Returns a sequence of all repos sidedocs knows about as 'files'.

  Each repo is expected to live in ~/.sidedocs.
  The user can have as many repos from as many sources as they'd like."
  []

  (let [sidedocs-dir (expand-home "~/.sidedocs")]
    (->> sidedocs-dir
         (io/file)
         (.list)
         (seq)
         (map #(io/file sidedocs-dir %)))))


(defn find-docstring
  "Locate appropriate side-loaded docstring for the namespace and the var.

  Returns: docstring or nil"
  [repo-location ns-name# var-name]

  (let [expected-file-location (io/file repo-location ns-name# (str var-name ".md"))]
    (if (.exists expected-file-location)
      (slurp expected-file-location)
      nil)))


(defn find-docstring-in-repos
  "Locates docstring for the given namespace and var in a list of docstring repos.

  The function will search through the given repos in the order and return the first
  result it finds.

  It is expected for the function to be used in conjunction with #'doc-repos to search
  through all configured documentation repos on disk."
  [repo-seq ns-name# var-name]

  (some #(find-docstring % ns-name# var-name) repo-seq))


;;------------------------------------------------------------------------------
;; nrepl middleware function
;;------------------------------------------------------------------------------
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
                               {:middleware :sidedocs})

            ns-name# (:ns msg)
            var-name# (:symbol msg)

            ;; Try to retrieve sided loaded docs given the ns and var names
            replacement-doc (find-docstring-in-repos (doc-repos) ns-name# var-name#)

            ;; Override the default docstring if the replacement docs can be found
            info-result (cond-> info-result
                          replacement-doc (merge {"doc" replacement-doc}))
            ]

        ;; Send the reply
        (nrepl.transport/send transport (response-for msg info-result)))

      ;; All other messages should pass through without processing
      ;; (handler msg)
      )))

(nrepl.middleware/set-descriptor! #'wrap-sidedocs
  ;; Install the middleware ahead of the "info" handler
  {:expects #{"info"}})


;;------------------------------------------------------------------------------
;; Test utilities
;;------------------------------------------------------------------------------
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

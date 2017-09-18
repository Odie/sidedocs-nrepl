(ns sidedocs-nrepl.middleware
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.misc :refer [response-for]]

            [clojure.tools.nrepl.server :as nrepl.server]
            [clojure.tools.nrepl.transport :as nrepl.transport]
            [clojure.tools.nrepl.middleware :as nrepl.middleware]

            [cider.nrepl.middleware.info]
            [cider.nrepl.middleware.util.error-handling]
            [cider.nrepl.middleware.util.meta]

            [clojure.java.io :as io]
            [clojure.string :as str]))



;;------------------------------------------------------------------------------
;; Functions *borrowed* from cider
;; These functions seem to have moved between versions 0.14 and 0.15
;;------------------------------------------------------------------------------

(defn- resolve-special
  "Return info for the symbol if it's a special form, or nil otherwise. Adds
  `:url` unless that value is explicitly set to `nil` -- the same behavior
  used by `clojure.repl/doc`."
  [sym]
  (try
    (let [sym (get '{& fn, catch try, finally try} sym sym)
          v   (meta (ns-resolve (find-ns 'clojure.core) sym))]
      (when-let [m (cond (special-symbol? sym) (#'clojure.repl/special-doc sym)
                         (:special-form v) v)]
        (assoc m
               :url (if (contains? m :url)
                      (when (:url m)
                        (str "https://clojure.org/" (:url m)))
                      (str "https://clojure.org/special_forms#" (:name m))))))
    (catch NoClassDefFoundError _)
    (catch Exception _)))


(defn- resolve-var
  "Returns "
  [ns sym]
  (if-let [ns (find-ns ns)]
    (try (ns-resolve ns sym)
         ;; Impl might try to resolve it as a class, which may fail
         (catch ClassNotFoundException _
           nil)
         ;; TODO: Preserve and display the exception info
         (catch Exception _
           nil))))


(defn- resolve-aliases
  [ns]
  (if-let [ns (find-ns ns)]
    (ns-aliases ns)))


(defn- resolve-sym-in-ns
  "Find the Var given the namespace symbol and sym.

  Returns: Var or nil"
  [ns-sym sym]
  (or
   (resolve-special sym)

   ;; it's a var
   (resolve-var ns-sym sym)

   ;; sym is an alias for another ns
   (get (resolve-aliases ns-sym) sym)

   ;; it's simply a full ns
   (find-ns sym)))

(defn- var-split
  "Given a Var, return the namespace and the symbol names"
  [var#]

  (-> (str var#)
      (subs 2)  ;; remove the "#'" characters
      (str/split #"/")))


(defn resolve-symbol-by-name
  "Given a symbol in a namespace, find the Var it refers to."
  [ns-name# sym-name]

  (resolve-sym-in-ns (symbol ns-name#) (symbol sym-name)))



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

  (let [expected-file-location (io/file repo-location ns-name# (str var-name ".adoc"))]
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
  (let [msg-count (atom 1)]
    (fn [{:keys [op transport] :as msg}]

      (if (= "info" op)
        ;; Unfortunately, nrepl middlewares don't seem to return a response map that we can
        ;; directly manipulate.
        ;; So to have cider.nrepl deal with the var info request, we have to call into
        ;; the cider nrepl innards directly.
        (let [info-result (merge (run-cider-nrepl-op cider.nrepl.middleware.info/info-reply msg)
                                 {:middleware :sidedocs})

              [ns-name# var-name#] (var-split (resolve-symbol-by-name (:ns msg) (:symbol msg)))

              ;; Try to retrieve sided loaded docs given the ns and var names
              replacement-doc (find-docstring-in-repos (doc-repos) ns-name# var-name#)

              ;; Override the default docstring if the replacement docs can be found
              info-result (cond-> info-result
                            replacement-doc (merge {"doc" replacement-doc}))
              ]

          ;; Send the reply
          (nrepl.transport/send transport (response-for msg info-result))
          )

        ;; All other messages should pass through without processing
        (handler msg)
        ))))

(nrepl.middleware/set-descriptor! #'wrap-sidedocs
  ;; Install the middleware ahead of the "info" handler
  {:expects #{"info"}})



;;------------------------------------------------------------------------------
;; Manual testing utilities
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
                  nrepl/combine-responses
                  clojure.pprint/pprint
                  )))


(defn- send-nrepl-message-to-server
  [server msg]

  (send-nrepl-message (.getLocalPort (:ss server)) msg))


(comment
  (with-open [server (start-server)]
    (send-nrepl-message-to-server server {:op "describe"}))

  (with-open [server (start-server)]
    (send-nrepl-message-to-server server {:op "eval" :code "(+ 2 3)"}))


  (with-open [server (apply start-server (map resolve middleware-list))]
    (time
     (send-nrepl-message-to-server server {:op "info" :symbol "str" :ns "clojure.core"})))

  (with-open [server (start-server #'cider.nrepl.middleware.info/wrap-info
                                   #'wrap-sidedocs
                      )]
    (time
     (send-nrepl-message-to-server server {:op "info" :ns (str *ns*) :symbol "str" })))

  )

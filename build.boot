(def project 'sidedocs-nrepl)
(def version "0.1.0-SNAPSHOT")

(set-env! :resource-paths #{"resources" "src"}
          :source-paths   #{"test"}
          :dependencies   '[[org.clojure/clojure "RELEASE"]
                            [adzerk/boot-test "RELEASE" :scope "test"]
                            [org.clojure/tools.nrepl "0.2.12"]
                            [adzerk/bootlaces "0.1.13"]])

(task-options!
 aot {:namespace   #{'sidedocs-nrepl.core}}
 pom {:project     project
      :version     version
      :description "Show sideloaded docstrings in cider!"
      :url         "http://example/FIXME"
      :scm         {:url "https://github.com/yourname/sidedocs-nrepl"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 jar {:main        'sidedocs-nrepl.core
      :file        (str "sidedocs-nrepl-" version "-standalone.jar")})

(require '[adzerk.bootlaces :refer :all])

(deftask build
  "Build the project locally as a JAR."
  [d dir PATH #{str} "the set of directories to write to (target)."]
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (aot) (pom) (uber) (jar) (target :dir dir))))

(deftask run
  "Run the project."
  [a args ARG [str] "the arguments for the application."]
  (require '[sidedocs-nrepl.core :as app])
  (apply (resolve 'app/-main) args))

(require '[adzerk.boot-test :refer [test]])

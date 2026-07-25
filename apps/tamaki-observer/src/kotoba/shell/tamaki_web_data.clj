(ns kotoba.shell.tamaki-web-data
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [kotoba.shell.tamaki-observer :as observer]))

(defn web-snapshot []
  (let [state (observer/snapshot)
        registry (:registry state)]
    {:observed-at (:observed-at state)
     :counts (select-keys registry [:total :west :github :rad :local])
     :orgs (:orgs registry)
     :repos (mapv #(select-keys % [:name :path :remote :west? :github? :rad?
                                   :local? :sync])
                  (:repos registry))
     :dependencies (:dependencies registry)
     :active-repos
     (mapv (fn [{:keys [path issue runs]}]
             {:path path :issue issue
              :runs (mapv #(select-keys % [:agent.run/id :agent.run/status
                                           :agent.run/model :agent.run/goal])
                          runs)})
           (:active-repos registry))
     :decisions (:decisions state)
     :campaigns (:campaigns state)
     :activity (:activity state)}))

(defn write-snapshot! [target]
  (let [target-file (io/file target)
        next-file (io/file (str target ".next"))]
    (.mkdirs (.getParentFile target-file))
    (spit next-file (json/write-str (web-snapshot)))
    (java.nio.file.Files/move
     (.toPath next-file) (.toPath target-file)
     (into-array java.nio.file.CopyOption
                 [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                  java.nio.file.StandardCopyOption/ATOMIC_MOVE]))))

(defn -main [& [target]]
  (when-not target
    (throw (ex-info "snapshot target is required" {})))
  (loop []
    (try
      (write-snapshot! target)
      (catch Exception e
        (binding [*out* *err*] (println "snapshot failed:" (.getMessage e)))))
    (Thread/sleep 1000)
    (recur)))

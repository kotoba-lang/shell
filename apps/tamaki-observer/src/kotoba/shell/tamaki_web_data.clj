(ns kotoba.shell.tamaki-web-data
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string]
            [kotoba.shell.tamaki-observer :as observer]
            [kotoba.tamaki.store :as store]))

(def active-statuses #{:queued :leased :running})

(defn- workspace-path [path]
  (let [root (some-> (System/getenv "KOTOBA_WORKSPACE_ROOT")
                     io/file .getAbsolutePath)
        absolute (some-> path io/file .getAbsolutePath)]
    (if (and root absolute (.startsWith absolute (str root java.io.File/separator)))
      (subs absolute (inc (count root)))
      path)))

(defn- run-project [run]
  (workspace-path
   (or (:agent.run/source-project run) (:agent.run/project run))))

(defn- run-context [events run]
  (let [receipts (filter #(= (:agent.run/id run) (:tamaki.event/run %)) events)]
    {:issue (or (some #(get-in % [:tamaki.event/data :issue/id]) receipts)
                (some->> (:agent.run/goal run)
                         (re-find #"[0-9a-f]{40}")))
     :loop (some #(get-in % [:tamaki.event/data :loop/id]) receipts)}))

(defn- discover-project-topologies [registry]
  (let [root (io/file (observer/workspace-root))
        candidates (->> (:repos registry)
                        (map #(io/file root (:path %) "docs"
                                      "revenue-growth-project.edn"))
                        (filter #(.isFile %))
                        (take 20))]
    (mapv
     (fn [file]
       (let [project (edn/read-string (slurp file))]
         {:id (str (:project/id project))
          :objective (:project/objective project)
          :metric (str (:project/metric project))
          :reverse-topology
          (mapv #(mapv str %) (:project/reverse-topology project))
          :execution-waves
          (mapv #(mapv str %) (:project/execution-waves project))
          :issues
          (mapv (fn [issue]
                  {:key (str (:issue/key issue))
                   :rad (:issue/rad issue)
                   :repo (:issue/repo issue)
                   :status (some-> (:issue/status issue) name)
                   :blockers (mapv str (:issue/blockers issue))})
                (:project/issues project))}))
     candidates)))

(defonce project-topologies (atom nil))

(defn- repo-stats [events runs campaigns]
  (let [run-by-id (into {} (map (juxt :agent.run/id identity)) runs)
        project-of #(some-> (get run-by-id (:tamaki.event/run %)) run-project)]
    (->> events
         (group-by project-of)
         (keep
          (fn [[project repo-events]]
            (when project
              (let [issues (set (keep #(get-in % [:tamaki.event/data :issue/id])
                                      (filter (comp #{:issue/discovered}
                                                    :tamaki.event/kind)
                                              repo-events)))
                    solved (set (keep #(get-in % [:tamaki.event/data :issue/id])
                                      (filter (comp #{:patch/integrated}
                                                    :tamaki.event/kind)
                                              repo-events)))
                    patches (count (filter (comp #{:patch/created}
                                                 :tamaki.event/kind)
                                           repo-events))
                    integrated (count (filter (comp #{:patch/integrated}
                                                    :tamaki.event/kind)
                                              repo-events))]
                {:path project
                 :issues-open (count (remove solved issues))
                 :patches-open (max 0 (- patches integrated))
                 :loops (count (filter #(= project
                                          (workspace-path
                                           (:tamaki.loop/project %)))
                                       campaigns))
                 :wip (count (filter
                              #(and (= project (run-project %))
                                    (active-statuses (:agent.run/status %)))
                              runs))}))))
         vec)))

(defn web-snapshot []
  (let [state (observer/snapshot)
        registry (:registry state)
        events (store/read-local-events (observer/state-dir))
        runs (:runs state)
        campaigns (:campaigns state)
        agents (->> runs
                    (filter #(active-statuses (:agent.run/status %)))
                    (mapv (fn [run]
                            (merge
                             {:id (:agent.run/id run)
                              :status (:agent.run/status run)
                              :model (:agent.run/model run)
                              :runner (:agent.run/runner run)
                              :project (run-project run)
                              :execution-project (:agent.run/project run)
                              :goal (:agent.run/goal run)
                              :parent (:agent.run/parent run)}
                             (run-context events run)))))]
    {:observed-at (:observed-at state)
     :counts (select-keys registry [:total :west :github :rad :local])
     :orgs (:orgs registry)
     :repos (mapv #(select-keys % [:name :path :remote :west? :github? :rad?
                                   :local? :sync])
                  (:repos registry))
     :dependencies (:dependencies registry)
     :projects (or @project-topologies
                   (reset! project-topologies
                           (discover-project-topologies registry)))
     :agents agents
     :loops (mapv (fn [campaign]
                    {:id (:tamaki.loop/id campaign)
                     :status (:tamaki.loop/status campaign)
                     :project (workspace-path (:tamaki.loop/project campaign))
                     :objective (:tamaki.loop/objective campaign)
                     :model (:tamaki.loop/model campaign)
                     :runner (:tamaki.loop/runner campaign)
                     :cycles (:tamaki.loop/cycles campaign)
                     :max-cycles (:tamaki.loop/max-cycles campaign)})
                  campaigns)
     :repo-stats (repo-stats events runs campaigns)
     :active-repos
     (mapv (fn [{:keys [path issue runs]}]
             {:path path :issue issue
              :runs (mapv #(select-keys % [:agent.run/id :agent.run/status
                                           :agent.run/model :agent.run/goal])
                          runs)})
           (:active-repos registry))
     :decisions (:decisions state)
     :campaigns (:campaigns state)
     :activity
     (->> events
          (sort-by :tamaki.event/at >)
          (take 80)
          (mapv (fn [event]
                  {:id (:tamaki.event/id event)
                   :at (:tamaki.event/at event)
                   :run (:tamaki.event/run event)
                   :kind (or (get-in event
                                     [:tamaki.event/data :activity/kind])
                             (:tamaki.event/kind event))
                   :state (get-in event
                                  [:tamaki.event/data :activity/state])
                   :text (get-in event [:tamaki.event/data :activity/text])
                   :issue (get-in event [:tamaki.event/data :issue/id])
                   :patch (get-in event [:tamaki.event/data :patch/id])})))
     :model-usage
     (let [run-by-id (into {} (map (juxt :agent.run/id identity)) runs)]
       (->> events
            (filter #(= :agent/activity (:tamaki.event/kind %)))
            (keep (fn [event]
                    (let [data (:tamaki.event/data event)
                          run (get run-by-id (:tamaki.event/run event))]
                      (when (some #(contains? data %)
                                  [:usage/input :usage/output
                                   :usage/cache-read :usage/cache-write])
                        {:provider (or (:agent.run/runner run)
                                       (some-> (:agent.run/model run)
                                               (clojure.string/split #":")
                                               first)
                                       "unknown")
                         :input (or (:usage/input data) 0)
                         :output (or (:usage/output data) 0)
                         :cache-read (or (:usage/cache-read data) 0)
                         :cache-write (or (:usage/cache-write data) 0)}))))
            (group-by :provider)
            (map (fn [[provider rows]]
                   {:provider provider
                    :input (reduce + (map :input rows))
                    :output (reduce + (map :output rows))
                    :cache-read (reduce + (map :cache-read rows))
                    :cache-write (reduce + (map :cache-write rows))
                    :remaining :unknown}))
            vec))}))

(defn write-snapshot! [target]
  (let [target-file (io/file target)
        next-file (io/file (str target ".next"))]
    (.mkdirs (.getParentFile target-file))
    (let [snapshot (web-snapshot)]
      (spit next-file (json/write-str snapshot))
      (spit (io/file (.getParentFile target-file) "topology.edn")
            (pr-str (select-keys snapshot
                                 [:repos :dependencies :agents :loops
                                  :repo-stats]))))
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

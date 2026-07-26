(ns tamaki.observer.app
  (:require ["three" :as THREE]
            ["three/addons/controls/OrbitControls.js" :refer [OrbitControls]]
            [clojure.string :as str]
            [datascript.core :as d]
            [re-frame.core :as rf]
            [reagent.dom :as rdom]
            [tamaki.observer.style :as style]
            [org-threejs.core :as three]))

(defonce runtime (atom nil))
(defonce audio-runtime (atom {:enabled? false :step 0}))
(defonce topology-signature (atom nil))
(defonce issue-node-positions (atom {}))
(def topology-schema
  {:repo/path {:db/unique :db.unique/identity}
   :agent/id {:db/unique :db.unique/identity}
   :loop/id {:db/unique :db.unique/identity}})
(defonce topology-db (d/create-conn topology-schema))
(defonce snapshot-repos (atom []))

(defn label [value fallback]
  (cond
    (keyword? value) (name value)
    (string? value) value
    :else fallback))

(rf/reg-event-db :snapshot
  (fn [db [_ snapshot]] (assoc db :snapshot snapshot)))
(rf/reg-event-db :select-repo
  (fn [db [_ repo]] (assoc db :selected repo)))
(rf/reg-event-db :group-mode
  (fn [db [_ mode]] (assoc db :group-mode mode)))
(rf/reg-event-db :activity-agent
  (fn [db [_ agent-id]] (assoc db :activity-agent agent-id)))
(rf/reg-sub :snapshot (fn [db _] (:snapshot db)))
(rf/reg-sub :selected (fn [db _] (:selected db)))
(rf/reg-sub :group-mode (fn [db _] (or (:group-mode db) :org)))
(rf/reg-sub :activity-agent
  (fn [db _] (or (:activity-agent db) "all")))

(defn index-datoms! [snapshot]
  (d/reset-conn! topology-db (d/empty-db topology-schema))
  (let [stats (into {} (map (juxt :path identity)) (:repo-stats snapshot))]
    (reset! snapshot-repos
            (mapv (fn [repo]
                    (merge repo
                           (select-keys (get stats (:path repo))
                                        [:issues-open :patches-open :wip])))
                  (:repos snapshot)))
    (d/transact!
     topology-db
     (concat
      (map-indexed
       (fn [index repo]
         (let [stat (get stats (:path repo))]
           {:db/id (- (inc index))
            :repo/path (:path repo) :repo/name (:name repo)
            :repo/org (:remote repo) :repo/sync (or (:sync repo) "unmanaged")
            :repo/issues (or (:issues-open stat) 0)
            :repo/patches (or (:patches-open stat) 0)
            :repo/wip (or (:wip stat) 0)}))
       (:repos snapshot))
      (map-indexed
       (fn [index {:keys [from to]}]
         {:db/id (- (+ 1000001 index)) :dep/from from :dep/to to})
       (:dependencies snapshot))
      (keep-indexed (fn [index agent]
              (let [id (or (:agent.run/id agent) (:id agent))
                    project (or (:agent.run/source-project agent)
                                (:source-project agent)
                                (:agent.run/project agent) (:project agent))]
                (when (and id project)
                  {:db/id (- (+ 2000001 index))
                   :agent/id id :agent/project project
                   :agent/model (or (:agent.run/model agent) (:model agent) "default")
                   :agent/runner (or (:agent.run/runner agent) (:runner agent) "default")
                   :agent/status (label (or (:agent.run/status agent)
                                            (:status agent)) "unknown")
                   :agent/goal (or (:agent.run/goal agent) (:goal agent) "")
                   :agent/issue (or (:issue agent) "")})))
            (:agents snapshot))
      (keep-indexed (fn [index loop]
              (let [id (or (:tamaki.loop/id loop) (:id loop))
                    project (or (:tamaki.loop/project loop) (:project loop))]
                (when (and id project)
                  {:db/id (- (+ 3000001 index))
                   :loop/id id :loop/project project
                   :loop/runner (or (:tamaki.loop/runner loop) (:runner loop) "default")
                   :loop/model (or (:tamaki.loop/model loop) (:model loop) "default")
                   :loop/status (label (or (:tamaki.loop/status loop)
                                          (:status loop)) "unknown")})))
            (:loops snapshot))))))

(defn queried-repos []
  (->> (d/q '[:find ?path ?name ?org ?sync ?issues ?patches ?wip
              :where
              [?e :repo/path ?path] [?e :repo/name ?name]
              [?e :repo/org ?org] [?e :repo/sync ?sync]
              [?e :repo/issues ?issues] [?e :repo/patches ?patches]
              [?e :repo/wip ?wip]]
            @topology-db)
       (mapv (fn [[path name org sync issues patches wip]]
               {:path path :name name :remote org :sync sync
                :issues-open issues :patches-open patches :wip wip}))))

(defn visible-repos []
  (let [queried (queried-repos)]
    (if (= (count queried) (count @snapshot-repos))
      queried
      @snapshot-repos)))

(defn queried-agents []
  (mapv (fn [[id project model runner status issue goal]]
          {:agent.run/id id :agent.run/project project :agent.run/model model
           :agent.run/runner runner :agent.run/status status
           :agent.run/goal goal :issue issue})
        (d/q '[:find ?id ?project ?model ?runner ?status ?issue ?goal
               :where [?e :agent/id ?id] [?e :agent/project ?project]
               [?e :agent/model ?model] [?e :agent/runner ?runner]
               [?e :agent/status ?status]
               [?e :agent/issue ?issue] [?e :agent/goal ?goal]]
             @topology-db)))

(defn queried-loops []
  (mapv (fn [[id project status runner model]]
          {:tamaki.loop/id id :tamaki.loop/project project
           :tamaki.loop/status status :tamaki.loop/runner runner
           :tamaki.loop/model model})
        (d/q '[:find ?id ?project ?status ?runner ?model
               :where [?e :loop/id ?id] [?e :loop/project ?project]
               [?e :loop/status ?status] [?e :loop/runner ?runner]
               [?e :loop/model ?model]]
             @topology-db)))

(def colors
  {:active 0x35ff8a :different 0xff922e :synced 0x208cff
   :remote 0x76658f :platform 0x28143c :edge 0x48dcff})

(defn material [kind]
  (three/standard-material
   {:color (get colors kind) :metallic 0.32 :roughness 0.38
    :emissive (if (= kind :active) 1.8 0.12)
    :transparent true :opacity (if (= kind :remote) 0.58 1)}))

(defn status [repo active-paths]
  (cond
    (contains? active-paths (:path repo)) :active
    (= "different" (:sync repo)) :different
    (= "synced" (:sync repo)) :synced
    :else :remote))

(defn group-key [mode repo]
  (case mode
    :project (let [parts (str/split (:path repo) #"/")]
               (if (= "projects" (first parts))
                 (str "project/" (second parts))
                 (str (first parts) "/" (second parts))))
    :sync (str "sync/" (:sync repo))
    (:remote repo)))

(defn dependency-ranks [dependencies]
  (loop [ranks {} remaining dependencies pass 0]
    (if (or (empty? remaining) (> pass 100))
      ranks
      (let [next-ranks
            (reduce (fn [result {:keys [from to]}]
                      (assoc result from
                             (max (get result from 0)
                                  (inc (get result to 0)))))
                    ranks remaining)]
        (if (= ranks next-ranks)
          ranks
          (recur next-ranks remaining (inc pass)))))))

(defn clear! [group]
  (doseq [child (array-seq (.-children group))]
    (.remove group child)
    (three/dispose-object! child)))

(defn add-dependency-lines! [root positions dependencies]
  (doseq [{:keys [from to]} dependencies
          :let [a (get positions from) b (get positions to)]
          :when (and a b)]
    (let [geometry (THREE/BufferGeometry.)
          points #js [(THREE/Vector3. (:x a) 0.8 (:z a))
                      (THREE/Vector3. (:x b) 0.8 (:z b))]
          _ (.setFromPoints geometry points)
          line (THREE/Line. geometry
                            (THREE/LineBasicMaterial.
                             #js {:color (:edge colors)
                                  :transparent true :opacity 0.8}))]
      (.add root line))))

(declare text-sprite)

(defn add-project-topologies! [root positions projects]
  (reset! issue-node-positions {})
  (doseq [project projects
          :let [issues (into {} (map (juxt :key identity)) (:issues project))
                issue-positions (atom {})]]
    (doseq [[level keys] (map-indexed vector (:reverse-topology project))
            [column key] (map-indexed vector keys)
            :let [issue (get issues key)
                  base (get positions (:repo issue))]
            :when (and issue base)]
      (let [x (+ (:x base) (* (- column (/ (dec (count keys)) 2)) 0.9))
            y (+ (:height base) 1.2 (* level 0.85))
            z (+ (:z base) 0.7)
            status-color (case (:status issue)
                           "closed" 0x42f58d
                           "in-progress" 0x42a5ff
                           0xffd45a)
            objective? (= "objective" (:kind issue))
            node (three/mesh
                  (if objective?
                    (THREE/IcosahedronGeometry. 0.48 1)
                    (THREE/OctahedronGeometry. 0.28))
                  (three/standard-material
                   {:color (if objective? 0xf4a8ff status-color)
                    :emissive (if objective? 2.2 1.5) :roughness 0.25}))
            caption (text-sprite
                     (if objective?
                       (str "OBJECTIVE · " (or (:title issue) key))
                       (str "ISSUE · " (or (:rad issue) key)))
                     (if objective? "#f4a8ff" "#ffd45a"))]
        (three/set-position! node x y z)
        (three/set-position! caption x (+ y 0.75) z)
        (three/set-scale! caption (if objective? 5.6 3.4)
                          (if objective? 1.05 0.72) 1)
        (set! (.. node -userData -repo)
              (clj->js {:path (:repo issue) :issue (:rad issue)
                        :project (:id project) :issue-key key}))
        (swap! issue-positions assoc key {:x x :y y :z z})
        (swap! issue-node-positions assoc key {:x x :y y :z z})
        (when (:rad issue)
          (swap! issue-node-positions assoc (:rad issue) {:x x :y y :z z}))
        (.add root caption)
        (.add root node)))
    (doseq [[key issue] issues
            blocker (:blockers issue)
            :let [a (get @issue-positions key)
                  b (get @issue-positions blocker)]
            :when (and a b)]
      (let [geometry (doto (THREE/BufferGeometry.)
                       (.setFromPoints
                        #js [(THREE/Vector3. (:x a) (:y a) (:z a))
                             (THREE/Vector3. (:x b) (:y b) (:z b))]))
            line (THREE/Line.
                  geometry
                  (THREE/LineBasicMaterial.
                   #js {:color 0xffd45a :transparent true :opacity 0.85}))]
        (.add root line)))
    (doseq [{:keys [actor from to]} (:walks project)
            :let [a (get @issue-positions from)
                  b (get @issue-positions to)]
            :when (and a b)]
      (let [curve (THREE/QuadraticBezierCurve3.
                   (THREE/Vector3. (:x a) (:y a) (:z a))
                   (THREE/Vector3. (/ (+ (:x a) (:x b)) 2)
                                   (+ 1.4 (max (:y a) (:y b)))
                                   (/ (+ (:z a) (:z b)) 2))
                   (THREE/Vector3. (:x b) (:y b) (:z b)))
            geometry (THREE/TubeGeometry. curve 24 0.035 6 false)
            path (THREE/Mesh.
                  geometry
                  (THREE/MeshBasicMaterial.
                   #js {:color 0x35ff8a :transparent true :opacity 0.72}))]
        (set! (.. path -userData -walk)
              (clj->js {:actor actor :from from :to to}))
        (.add root path)))))

(def result-colors
  {:issue 0xffd45a :source 0x48dcff :radicle 0xc956ff
   :github 0xf5f5f5 :review 0x35ff8a :merge 0x208cff})

(defn add-result-graphs! [root positions graphs]
  (doseq [[graph-index graph] (map-indexed vector graphs)
          :let [base (get positions (:result/project graph))
                nodes (:result/nodes graph)]
          :when base]
    (let [node-positions
          (into {}
                (map-indexed
                 (fn [index node]
                   [(:result.node/id node)
                    {:x (+ (:x base) (* (- index (/ (dec (count nodes)) 2))
                                        1.15))
                     :y (+ (:height base) 3.0 (* 0.28 (mod graph-index 3)))
                     :z (+ (:z base) 2.6 (* 0.7 graph-index))}])
                 nodes))]
      (doseq [node nodes
              :let [{:keys [x y z]} (get node-positions
                                          (:result.node/id node))
                    kind (keyword (name (:result.node/type node)))
                    color (get result-colors kind 0xffffff)
                    object (three/mesh
                            (case kind
                              :source (THREE/BoxGeometry. 0.5 0.5 0.5)
                              :github (THREE/TorusKnotGeometry. 0.24 0.07 40 8)
                              :review (THREE/DodecahedronGeometry. 0.3)
                              :merge (THREE/SphereGeometry. 0.32 14 10)
                              (THREE/OctahedronGeometry. 0.3))
                            (three/standard-material
                             {:color color :emissive 1.7 :roughness 0.24}))
                    caption (text-sprite
                             (str (str/upper-case (name kind)) " · "
                                  (let [value (str (:result.node/value node))]
                                    (subs value 0 (min 12 (count value)))))
                             (str "#" (.toString color 16)))]]
        (three/set-position! object x y z)
        (three/set-position! caption x (+ y 0.62) z)
        (three/set-scale! caption 3.1 0.65 1)
        (set! (.. object -userData -result) (clj->js node))
        (.add root object)
        (.add root caption))
      (doseq [edge (:result/edges graph)
              :let [a (get node-positions (:result.edge/from edge))
                    b (get node-positions (:result.edge/to edge))]
              :when (and a b)]
        (let [geometry (doto (THREE/BufferGeometry.)
                         (.setFromPoints
                          #js [(THREE/Vector3. (:x a) (:y a) (:z a))
                               (THREE/Vector3. (:x b) (:y b) (:z b))]))
              line (THREE/Line.
                    geometry
                    (THREE/LineBasicMaterial.
                     #js {:color 0x89f7ff :transparent true
                          :opacity 0.9}))]
          (.add root line))))))

(defn text-sprite [text color]
  (let [canvas (.createElement js/document "canvas")
        _ (set! (.-width canvas) 512)
        _ (set! (.-height canvas) 128)
        context (.getContext canvas "2d")
        _ (set! (.-fillStyle context) "rgba(9,6,17,.82)")
        _ (.fillRect context 0 0 512 128)
        _ (set! (.-strokeStyle context) color)
        _ (set! (.-lineWidth context) 5)
        _ (.strokeRect context 3 3 506 122)
        _ (set! (.-font context) "600 34px -apple-system, sans-serif")
        _ (set! (.-fillStyle context) "#f8f5ff")
        _ (set! (.-textAlign context) "center")
        _ (set! (.-textBaseline context) "middle")
        _ (.fillText context text 256 64)
        texture (THREE/CanvasTexture. canvas)
        sprite (THREE/Sprite.
                (THREE/SpriteMaterial.
                 #js {:map texture :transparent true :depthTest false}))]
    (three/set-scale! sprite 5.4 1.35 1)
    sprite))

(defn add-system-dynamics! [root dynamics]
  (let [stocks (:stocks dynamics)
        stock-x (into {} (map-indexed
                          (fn [index stock]
                            [(:id stock) (+ -12 (* index 8))])
                          stocks))
        ;; Keep the dynamics lane between repository group rows and toward the
        ;; camera. At negative Z it is depth-occluded by the large repo grids.
        z 10
        particles (atom [])]
    (doseq [stock stocks
            :let [x (get stock-x (:id stock))
                  value (or (:value stock) 0)
                  color (js/parseInt (subs (:color stock) 1) 16)
                  fill-height (+ 0.35 (min 5 (* 1.05 (js/Math.sqrt value))))
                  frame (THREE/Mesh.
                         (THREE/BoxGeometry. 4.8 5.7 4.8)
                         (THREE/MeshBasicMaterial.
                          #js {:color color :wireframe true
                               :transparent true :opacity 0.35}))
                  fill (three/mesh
                        (THREE/BoxGeometry. 4.2 fill-height 4.2)
                        (three/standard-material
                         {:color color :emissive 0.72 :roughness 0.28
                          :transparent true :opacity 0.82}))
                  title (text-sprite
                         (str (:label stock) " · " value " " (:unit stock))
                         (:color stock))]]
      (three/set-position! frame x 2.65 z)
      (three/set-position! fill x (/ fill-height 2) z)
      (three/set-position! title x 6.15 z)
      (set! (.. fill -userData -dynamics)
            (clj->js {:type "stock" :stock stock}))
      (.add root frame)
      (.add root fill)
      (.add root title))
    (doseq [flow (:flows dynamics)
            :let [from-x (or (get stock-x (:from flow)) -18)
                  to-x (get stock-x (:to flow))
                  rate (or (:rate flow) 0)]
            :when to-x]
      (let [a (THREE/Vector3. (+ from-x 2.5) 2.6 z)
            b (THREE/Vector3. (- to-x 2.5) 2.6 z)
            distance (.distanceTo a b)
            pipe (THREE/Mesh.
                  (THREE/CylinderGeometry. 0.09 0.09 distance 10)
                  (THREE/MeshBasicMaterial.
                   #js {:color (if (pos? rate) 0x79ffa8 0x51465f)
                        :transparent true :opacity 0.7}))
            arrow (THREE/Mesh.
                   (THREE/ConeGeometry. 0.28 0.7 12)
                   (THREE/MeshBasicMaterial.
                    #js {:color (if (pos? rate) 0x79ffa8 0x51465f)}))
            midpoint (doto (THREE/Vector3.) (.addVectors a b) (.multiplyScalar 0.5))
            flow-label (text-sprite
                        (str (:label flow) " · " rate "/h")
                        (if (pos? rate) "#79ffa8" "#81778e"))]
        (three/set-position! pipe (.-x midpoint) (.-y midpoint) (.-z midpoint))
        (set! (.. pipe -rotation -z) (/ js/Math.PI 2))
        (three/set-position! arrow (.-x b) (.-y b) (.-z b))
        (set! (.. arrow -rotation -z) (- (/ js/Math.PI 2)))
        (three/set-position! flow-label (.-x midpoint) 3.8 z)
        (.add root pipe)
        (.add root arrow)
        (.add root flow-label)
        (doseq [index (range (min 7 rate))
                :let [dot (THREE/Mesh.
                           (THREE/SphereGeometry. 0.16 10 8)
                           (THREE/MeshBasicMaterial.
                            #js {:color 0xffffff}))]]
          (.add root dot)
          (swap! particles conj
                 {:object dot :from a :to b
                  :phase (/ index (max 1 (min 7 rate)))
                  :speed (+ 0.18 (* 0.025 rate))}))))
    (let [title (text-sprite "SYSTEM DYNAMICS · STOCK → FLOW" "#d9b2ff")]
      (three/set-position! title 0 8.2 z)
      (three/set-scale! title 9.2 1.65 1)
      (.add root title))
    (swap! runtime assoc :flow-particles @particles)))

(def runner-style
  {"codex" {:body 0x27d8ff :accent 0xc6f7ff :head :visor}
   "claude" {:body 0xff9b52 :accent 0xffe1b7 :head :antenna}
   "claude-zai" {:body 0xa875ff :accent 0xe2d2ff :head :crown}
   "grok" {:body 0xff4f72 :accent 0xffc2cf :head :visor}})

(defn agent-runner [agent]
  (let [runner (:agent.run/runner agent)
        model (:agent.run/model agent)]
    (if (and runner (not= runner "default"))
      runner
      (or (some-> model (str/split #":") first not-empty) "agent"))))

(defn character-part [geometry color emissive]
  (three/mesh
   geometry
   (three/standard-material
    {:color color :emissive emissive :metallic 0.28 :roughness 0.34})))

(defn make-agent-character [agent]
  (let [runner (agent-runner agent)
        {:keys [body accent head]} (get runner-style runner
                                        {:body 0x35ff8a
                                         :accent 0xd5ffe3 :head :visor})
        actor (three/group)
        torso (character-part (THREE/CapsuleGeometry. 0.32 0.55 5 10)
                              body 0.65)
        face (character-part (THREE/SphereGeometry. 0.34 16 12)
                             accent 0.55)
        visor (character-part (THREE/BoxGeometry. 0.48 0.16 0.14)
                              0x101525 1.2)
        left-arm (character-part (THREE/CapsuleGeometry. 0.09 0.46 4 8)
                                 body 0.45)
        right-arm (character-part (THREE/CapsuleGeometry. 0.09 0.46 4 8)
                                  body 0.45)
        left-leg (character-part (THREE/CapsuleGeometry. 0.11 0.42 4 8)
                                 0x293145 0.25)
        right-leg (character-part (THREE/CapsuleGeometry. 0.11 0.42 4 8)
                                  0x293145 0.25)
        core (character-part (THREE/SphereGeometry. 0.11 10 8)
                             accent 1.8)
        beacon (THREE/Mesh.
                (THREE/TorusGeometry. 0.52 0.035 8 28)
                (THREE/MeshBasicMaterial.
                 #js {:color body :transparent true :opacity 0.72}))
        clickable (clj->js agent)]
    (three/set-position! torso 0 0.88 0)
    (three/set-position! face 0 1.55 0)
    (three/set-position! visor 0 1.58 0.29)
    (three/set-position! left-arm -0.42 0.9 0)
    (three/set-position! right-arm 0.42 0.9 0)
    (three/set-position! left-leg -0.18 0.28 0)
    (three/set-position! right-leg 0.18 0.28 0)
    (three/set-position! core 0 0.92 0.31)
    (three/set-rotation-x! beacon (/ js/Math.PI 2))
    (three/set-position! beacon 0 0.06 0)
    (doseq [part [torso face visor left-arm right-arm left-leg right-leg core
                  beacon]]
      (aset (.-userData ^js part) "repo" clickable)
      (.add actor part))
    (when (contains? #{:antenna :crown} head)
      (let [stem (character-part
                  (THREE/CylinderGeometry. 0.025 0.025 0.35 8)
                  body 0.8)
            tip (character-part
                 (if (= head :crown)
                   (THREE/OctahedronGeometry. 0.13)
                   (THREE/SphereGeometry. 0.1 10 8))
                 accent 1.8)]
        (three/set-position! stem 0 1.95 0)
        (three/set-position! tip 0 2.16 0)
        (aset (.-userData ^js stem) "repo" clickable)
        (aset (.-userData ^js tip) "repo" clickable)
        (.add actor stem)
        (.add actor tip)))
    (let [nameplate (text-sprite
                     (str runner " · " (:agent.run/model agent))
                     (str "#" (.toString body 16)))]
      (three/set-scale! nameplate 3.3 0.78 1)
      (three/set-position! nameplate 0 2.75 0)
      (.add actor nameplate))
    {:actor actor :head face :left-arm left-arm :right-arm right-arm
     :left-leg left-leg :right-leg right-leg :beacon beacon}))

(defn rebuild-scene! [snapshot group-mode]
  (when-let [{:keys [repo-root]} @runtime]
    (clear! repo-root)
    (let [repos (visible-repos)
          ranks (dependency-ranks (:dependencies snapshot))
          grouped (group-by #(group-key group-mode %) repos)
          groups (sort (keys grouped))
          group-columns (min 3 (max 1 (js/Math.ceil
                                        (js/Math.sqrt (count groups)))))
          active-by-path (into {} (map (juxt :path identity) (:active-repos snapshot)))
          active-paths (set (keys active-by-path))
          positions (atom {})]
      (doseq [[group-index group] (map-indexed vector groups)
              :let [group-repos (->> (get grouped group)
                                     (sort-by (juxt #(get ranks (:path %) 0)
                                                    :name))
                                     vec)
                    index-by-path (into {} (map-indexed
                                            (fn [i repo] [(:path repo) i])
                                            group-repos))
                    columns (max 1 (js/Math.ceil (js/Math.sqrt (count group-repos))))
                    col (mod group-index group-columns)
                    row (quot group-index group-columns)
                    origin-x (* (- col (/ (dec group-columns) 2)) 22)
                    origin-z (* row 20)
                    extent (+ 1 (* columns 0.32))
                    platform (three/mesh
                              (THREE/BoxGeometry. extent 0.32 extent)
                              (material :platform))]]
        (three/set-position! platform origin-x -0.2 origin-z)
        (.add repo-root platform)
        (doseq [[[kind congestion] kind-repos]
                (group-by (fn [repo]
                            [(status repo active-paths)
                             (min 6 (+ (:issues-open repo)
                                       (:patches-open repo)))])
                          group-repos)
                :let [height (if (= kind :active)
                               0.8
                               (+ 0.18 (* congestion 0.12)))
                      geometry (THREE/BoxGeometry. 0.25 height 0.25)
                      mesh (THREE/InstancedMesh. geometry (material kind)
                                                 (count kind-repos))
                      matrix (THREE/Matrix4.)
                      repo-array (array)]]
          (doseq [[instance-id repo] (map-indexed vector kind-repos)
                  :let [repo-index (get index-by-path (:path repo))
                        x (+ origin-x (* (- (mod repo-index columns)
                                             (/ (dec columns) 2)) 0.32))
                        z (+ origin-z (* (- (quot repo-index columns)
                                             (/ (dec columns) 2)) 0.32))
                        y (/ height 2)]]
            (.setPosition matrix x y z)
            (.setMatrixAt mesh instance-id matrix)
            (.push repo-array (clj->js (merge repo (get active-by-path (:path repo)))))
            (swap! positions assoc (:path repo) {:x x :z z :height height}))
          (set! (.. mesh -userData -repos) repo-array)
          (set! (.. mesh -instanceMatrix -needsUpdate) true)
          (.add repo-root mesh)))
      ;; A bright point-cloud layer keeps every repository legible even when
      ;; thousands of very small instanced bars are viewed from far away.
      (let [geometry (THREE/BufferGeometry.)
            vertices (->> repos
                          (mapcat (fn [repo]
                                    (when-let [{:keys [x z height]}
                                               (get @positions (:path repo))]
                                      [x (+ height 0.08) z])))
                          (into-array))
            attribute (THREE/Float32BufferAttribute. vertices 3)
            points (THREE/Points.
                    geometry
                    (THREE/PointsMaterial.
                     #js {:color 0x9b7bc4 :size 0.22
                          :sizeAttenuation true :transparent true
                          :opacity 0.95}))]
        (.setAttribute geometry "position" attribute)
        (.add repo-root points))
      (add-dependency-lines! repo-root @positions (:dependencies snapshot))
      (add-project-topologies! repo-root @positions (:projects snapshot))
      (add-result-graphs! repo-root @positions (:results snapshot))
      (add-system-dynamics! repo-root (:system-dynamics snapshot))
      (let [actor-animations (atom [])]
       (doseq [[agent-index agent] (map-indexed vector (queried-agents))
              :let [position (or (get @issue-node-positions (:issue agent))
                                 (get @positions (:agent.run/project agent)))]
              :when position]
        (let [{:keys [actor] :as character} (make-agent-character agent)
              phase (* agent-index 2.399)
              radius (+ 1.35 (* 0.35 (mod agent-index 3)))
              ;; Actors live on a readable central air-deck; the beam retains
              ;; their exact repo ownership even when that repo is off-screen.
              base (THREE/Vector3. (+ -11 (* 4.8 (mod agent-index 4)))
                                   (+ 8.0 (* 2.5 (quot agent-index 4)))
                                   -2.0)
              style (get runner-style (agent-runner agent)
                         {:body 0x35ff8a})
              orbit (THREE/Mesh.
                     (THREE/TorusGeometry. radius 0.035 8 48)
                     (THREE/MeshBasicMaterial.
                      #js {:color (:body style) :transparent true
                           :opacity 0.5}))
              beam-geometry
              (doto (THREE/BufferGeometry.)
                (.setFromPoints
                 #js [(THREE/Vector3. (:x position)
                                     (or (:y position)
                                         (:height position) 0)
                                     (:z position))
                      (THREE/Vector3. (.-x base) (.-y base) (.-z base))]))
              beam (THREE/Line.
                    beam-geometry
                    (THREE/LineDashedMaterial.
                     #js {:color (:body style) :transparent true
                          :opacity 0.65 :dashSize 0.28 :gapSize 0.18}))]
          (.computeLineDistances beam)
          (three/set-rotation-x! orbit (/ js/Math.PI 2))
          (three/set-position! orbit (.-x base) (.-y base) (.-z base))
          (three/set-scale! actor 2.0 2.0 2.0)
          (three/set-position! actor
                               (+ (.-x base) (* radius (js/Math.cos phase)))
                               (.-y base)
                               (+ (.-z base) (* radius (js/Math.sin phase))))
          (.add repo-root beam)
          (.add repo-root orbit)
          (.add repo-root actor)
          (swap! actor-animations conj
                 (merge character
                        {:base base :phase phase :radius radius
                         :speed (+ 0.28 (* 0.04 (mod agent-index 4)))
                         :working? (= "running" (:agent.run/status agent))}))))
       (swap! runtime assoc :actor-animations @actor-animations))
      (doseq [loop (queried-loops)
              :let [position (get @positions (:tamaki.loop/project loop))]
              :when position]
        (let [ring (three/mesh
                    (THREE/TorusGeometry. 0.48 0.055 8 28)
                    (three/standard-material
                     {:color 0xc956ff :emissive 1.1 :roughness 0.3}))]
          (three/set-rotation-x! ring (/ js/Math.PI 2))
          (three/set-position! ring (:x position)
                               (+ (:height position) 0.18) (:z position))
          (.add repo-root ring))))))

(defn render-overlay! [snapshot selected group-mode]
  (let [{:keys [total west github rad]} (:counts snapshot)
        active (first (:active-repos snapshot))
        metrics (.getElementById js/document "metrics")
        details (.getElementById js/document "details")
        model-usage (.getElementById js/document "model-usage")
        dynamics-panel (.getElementById js/document "system-dynamics")
        grouping (.getElementById js/document "grouping")
        repo (or selected active)
        path (or (:path repo) (:agent.run/project repo))
        stat (some #(when (= path (:path %)) %) (:repo-stats snapshot))]
    (set! (.-value grouping) (label group-mode "org"))
    (set! (.-textContent metrics)
          (str (count (visible-repos)) "/" total " repo tiles · "
               (count (:actors snapshot)) " actors · "
               (count (:agents snapshot)) " agents · "
               (count (:loops snapshot)) " loops · WEST " west
               " · GitHub " github " · Radicle " rad))
    (set! (.-innerHTML details)
          (if repo
            (str "<strong>" path "</strong><br>"
                 "issue " (or (:issue repo) "—") "<br>"
                 "sync " (label (:sync repo) "active") "<br>"
                 "open issues " (or (:issues-open stat) 0)
                 " · PR/patches " (or (:patches-open stat) 0)
                 " · loops " (or (:loops stat) 0) "<br><br>"
                 (when (:agent.run/id repo)
                   (str "agent " (:agent.run/id repo) "<br>"
                        "runner " (or (:agent.run/runner repo) "default") "<br>"
                        "model " (or (:agent.run/model repo) "default") "<br>"
                        (:agent.run/goal repo)))
                 (when-let [run (first (:runs repo))]
                   (str "agent " (get run :agent.run/id) "<br>"
                        "model " (or (get run :agent.run/model) "default") "<br>"
                        (get run :agent.run/goal))))
            "Select a repository tile"))
    (let [usage-by-provider (into {} (map (juxt :provider identity))
                                  (:model-usage snapshot))]
      (set! (.-innerHTML model-usage)
            (apply str
                   (for [provider ["codex" "claude" "claude-zai" "grok"]
                         :let [usage (get usage-by-provider provider)]]
                     (str "<div class=\"usage-card\"><b>" provider "</b>"
                          "<span>in " (or (:input usage) 0)
                          " · out " (or (:output usage) 0) "</span>"
                          "<span><em>remaining "
                          (or (:remaining usage) "unknown")
                          "</em></span></div>")))))
    (when-let [actor-state (.getElementById js/document "actor-state")]
      (set! (.-innerHTML actor-state)
            (apply str
                   (for [{:keys [id desired running queued blocked spawn]}
                         (:actors snapshot)]
                     (str "<div class=\"actor-card\"><b>" id "</b>"
                          "desired " desired " · running " running
                          " · queued " queued " · blocked " blocked
                          (when (pos? spawn)
                            (str " · <span class=\"pressure\">need +"
                                 spawn "</span>"))
                          "</div>")))))
    (let [{:keys [stocks flows bottleneck failure-pressure backlog-delta
                  throughput business-status business-control-score
                  business-kpis]} (:system-dynamics snapshot)]
      (set! (.-innerHTML dynamics-panel)
            (str "<div class=\"dynamics-heading\"><b>System dynamics</b>"
                 "<span>1h flow · bottleneck " bottleneck
                 " · failure " (.toFixed (* 100 (or failure-pressure 0)) 0)
                 "%</span></div>"
                 "<div class=\"business-control\"><b>Revenue control</b>"
                 (if (= business-status "observed")
                   (str "<span>score "
                        (.toFixed (* 100 (or business-control-score 0)) 0)
                        "% · MRR ¥" (or (:mrr-jpy business-kpis) 0)
                        " · risk-adjusted ΔMRR ¥"
                        (or (:risk-adjusted-delta-mrr-jpy business-kpis) 0)
                        " · experiments "
                        (.toFixed (or (:experiments-per-week business-kpis) 0) 1)
                        "/week</span>")
                   "<span class=\"pressure\">KPI observation required</span>")
                 "</div>"
                 "<div class=\"stock-row\">"
                 (apply str
                        (for [{:keys [id label value unit color]} stocks]
                          (str "<div class=\"stock-card"
                               (when (= id bottleneck) " bottleneck")
                               "\" style=\"--stock:" color "\">"
                               "<small>" label "</small><strong>" value
                               "</strong><em>" unit "</em></div>")))
                 "</div><div class=\"flow-row\">"
                 (apply str
                        (for [{:keys [label rate]} flows]
                          (str "<span>→ " label " <b>" rate "/h</b></span>")))
                 "<span class=\"" (if (pos? backlog-delta) "pressure" "relief")
                 "\">backlog Δ " (if (pos? backlog-delta) "+" "") backlog-delta
                 "/h</span><span>throughput <b>" throughput "/h</b></span>"
                 "</div>")))))

(defn scene-signature [snapshot]
  (let [dynamics (:system-dynamics snapshot)]
    [(count (:repos snapshot))
     (mapv (juxt :path :issue) (:active-repos snapshot))
     (frequencies (map :sync (:repos snapshot)))
     (:dependencies snapshot)
     (:projects snapshot) (:agents snapshot) (:loops snapshot)
     (:results snapshot)
     (:repo-stats snapshot)
     ;; observed-at advances every second but does not change geometry.
     ;; Excluding it avoids disposing/recreating thousands of WebGL objects
     ;; continuously until the context becomes blank.
     (select-keys dynamics
                  [:stocks :flows :bottleneck :failure-pressure
                   :backlog-delta :throughput])]))

(defn apply-state! []
  (let [snapshot @(rf/subscribe [:snapshot])
        selected @(rf/subscribe [:selected])
        group-mode @(rf/subscribe [:group-mode])]
    (when snapshot
      (let [signature (scene-signature snapshot)]
        (when (not= signature @topology-signature)
          (reset! topology-signature signature)
          (rebuild-scene! snapshot group-mode)))
      (render-overlay! snapshot selected group-mode))))

(defn fetch-snapshot! []
  (-> (js/fetch (str "snapshot.json?t=" (.now js/Date))
                #js {:cache "no-store"})
      (.then #(.json %))
      (.then #(do (rf/dispatch-sync [:snapshot (js->clj % :keywordize-keys true)])
                  (apply-state!)))
      (.catch #(js/console.error "Tamaki snapshot" %))))

(declare play-sfx! spawn-pulse!)

(defn group-mode-changed! [event]
  (play-sfx! :navigate)
  (spawn-pulse! (.-clientX event) (.-clientY event))
  (rf/dispatch-sync [:group-mode (keyword (.. event -target -value))])
  (reset! topology-signature nil)
  (apply-state!))

(defn play-tone! [frequency duration volume wave]
  (when-let [context (:context @audio-runtime)]
    (when (= "running" (.-state context))
      (let [now (.-currentTime context)
            oscillator (.createOscillator context)
            gain (.createGain context)]
        (set! (.-type oscillator) wave)
        (.setValueAtTime (.-frequency oscillator) frequency now)
        (.setValueAtTime (.-gain gain) 0.0001 now)
        (.exponentialRampToValueAtTime (.-gain gain) volume (+ now 0.025))
        (.exponentialRampToValueAtTime (.-gain gain) 0.0001 (+ now duration))
        (.connect oscillator gain)
        (.connect gain (.-destination context))
        (.start oscillator now)
        (.stop oscillator (+ now duration 0.03))))))

(defn play-sfx! [kind]
  (when (:enabled? @audio-runtime)
    (case kind
      :select (do (play-tone! 523.25 0.12 0.035 "sine")
                  (js/setTimeout #(play-tone! 783.99 0.16 0.028 "sine") 55))
      :voice (do (play-tone! 392.0 0.18 0.04 "triangle")
                 (js/setTimeout #(play-tone! 587.33 0.22 0.03 "triangle") 90))
      :navigate (play-tone! 329.63 0.1 0.025 "sine")
      nil)))

(def ambient-notes [110.0 146.83 164.81 220.0 196.0 146.83 130.81 164.81])

(defn ambient-step! []
  (let [step (:step @audio-runtime)
        note (nth ambient-notes (mod step (count ambient-notes)))]
    (play-tone! note 1.8 0.012 "sine")
    (when (zero? (mod step 4))
      (play-tone! (/ note 2) 3.4 0.009 "triangle"))
    (swap! audio-runtime update :step inc)))

(defn update-sound-label! []
  (when-let [button (.getElementById js/document "sound-toggle")]
    (set! (.-textContent button)
          (if (:enabled? @audio-runtime) "♫ ambient on" "♫ ambient off"))))

(defn toggle-sound! []
  (if (:enabled? @audio-runtime)
    (do
      (when-let [timer (:timer @audio-runtime)] (js/clearInterval timer))
      (when-let [context (:context @audio-runtime)] (.suspend context))
      (swap! audio-runtime assoc :enabled? false :timer nil))
    (let [context (or (:context @audio-runtime)
                      (js/AudioContext.))]
      (.resume context)
      (swap! audio-runtime assoc
             :enabled? true :context context
             :timer (js/setInterval ambient-step! 900))
      (ambient-step!)
      (play-sfx! :select)))
  (update-sound-label!))

(defn spawn-pulse! [x y]
  (when-let [layer (.getElementById js/document "effects")]
    (let [pulse (.createElement js/document "span")]
      (set! (.-className pulse) "pulse")
      (set! (.. pulse -style -left) (str x "px"))
      (set! (.. pulse -style -top) (str y "px"))
      (.appendChild layer pulse)
      (js/setTimeout #(.remove pulse) 750))))

(defn submit-voice! [transcript]
  (when-not (str/blank? transcript)
    (play-sfx! :voice)
    (set! (.-textContent (.getElementById js/document "voice-status"))
          (str "queued · " transcript))
    (when-let [handler (some-> js/window .-webkit .-messageHandlers .-voice)]
      (.postMessage handler transcript))))

(defn start-voice! []
  (let [ctor (or (.-SpeechRecognition js/window)
                 (.-webkitSpeechRecognition js/window))]
    (if ctor
      (let [recognition (ctor.)]
        (set! (.-lang recognition) "ja-JP")
        (set! (.-interimResults recognition) false)
        (set! (.-continuous recognition) false)
        (set! (.-textContent (.getElementById js/document "voice-status"))
              "listening…")
        (set! (.-onresult recognition)
              (fn [event]
                (submit-voice!
                 (.. event -results (item 0) (item 0) -transcript))))
        (set! (.-onerror recognition)
              (fn [event]
                (set! (.-textContent
                       (.getElementById js/document "voice-status"))
                      (str "voice unavailable · " (.-error event)))
                (when-let [transcript
                           (js/prompt
                            "音声認識を利用できません。Tamaki への指示を入力してください")]
                  (submit-voice! transcript))))
        (.start recognition))
      (when-let [transcript
                 (js/prompt "Tamaki supervisor への指示を入力してください")]
        (submit-voice! transcript)))))

(defn short-agent-label [{:keys [agent-id agent-runner]}]
  (let [id (or agent-id "system")
        short-id (if (> (count id) 18)
                   (str (subs id 0 8) "…" (subs id (- (count id) 5)))
                   id)]
    (if (and agent-runner (not= agent-runner "system")
             (not= agent-runner id))
      (str agent-runner " · " short-id)
      short-id)))

(defn activity-panel []
  (let [snapshot @(rf/subscribe [:snapshot])
        selected-agent @(rf/subscribe [:activity-agent])
        events (:activity snapshot)
        agents (->> events
                    (reduce (fn [result event]
                              (assoc result (:agent-id event) event)) {})
                    vals
                    (sort-by (juxt :agent-runner :agent-id)))
        visible (if (= "all" selected-agent)
                  events
                  (filter #(= selected-agent (:agent-id %)) events))]
    [:<>
     [:div.activity-filters
      [:button {:class (when (= "all" selected-agent) "selected")
                :on-click #(rf/dispatch [:activity-agent "all"])}
       "all"]
      (for [{:keys [agent-id] :as agent} agents]
        ^{:key agent-id}
        [:button {:class (when (= agent-id selected-agent) "selected")
                  :title (str (:agent-runner agent) " · "
                              (:agent-model agent) " · " agent-id)
                  :on-click #(rf/dispatch [:activity-agent agent-id])}
         (short-agent-label agent)])]
     [:div#activity
      (if (seq visible)
        (for [{:keys [id at kind run issue patch text stream] :as event}
              (take 20 visible)]
          ^{:key (or id (str run "-" at "-" kind))}
          [:div.event
           [:time (.toLocaleTimeString (js/Date. at))]
           [:div.event-heading
            [:b (label kind "event")]
            [:span.stream (or stream "system")]]
           [:small.agent
            (str "[" (short-agent-label event) "] · "
                 (or text issue patch run "workspace"))]])
        [:div.activity-empty "この agent の activity を待機中…"])]]))

(defn shell-view []
  [:div {:class style/app}
   [:canvas#scene {:class style/scene}]
   [:div#effects {:class style/effects}]
   [:header {:class (str style/glass " " style/header)}
    [:h1 "Tamaki Observatory"]
    [:div#metrics.metrics "Connecting…"]
    [:label "Group"
     [:select#grouping {:on-change group-mode-changed!}
      [:option {:value "org"} "organization"]
      [:option {:value "project"} "project"]
      [:option {:value "sync"} "west sync"]]]
    [:div.voice-row
     [:button.voice-button {:type "button" :on-click start-voice!}
      "🎙 Tamaki に話す"]
     [:span#voice-status.voice-status
      "voice intent → supervisor queue"]
     [:button#sound-toggle.sound-button
      {:type "button" :on-click toggle-sound!}
      "♫ ambient off"]]
    [:div#actor-state.actor-state]
    [:div#model-usage.model-usage]]
   [:aside#inspector {:class (str style/glass " " style/inspector)}
    [:h2 "Workspace"]
    [:div#details.details "Select a repository tile"]
    [:h2.activity-title "Live activity"]
    [activity-panel]]
   [:section#system-dynamics
    {:class (str style/glass " " style/dynamics)}
    [:b "System dynamics"] [:span "Connecting…"]]
   [:div#legend {:class (str style/glass " " style/legend)}
    "drag rotate · wheel zoom · click repo/agent" [:br]
    [:span.live "◆ walking agent actor"] "　"
    [:span.sync "● synced"] "　"
    [:span.diff "● west Δ"] "　"
    [:span.loop "○ loop"] [:br]
    "animated actor beam = active repo · tile height = congestion"]])

(defn mount-shell! []
  (rdom/render [shell-view] (.getElementById js/document "app")))

(defn refresh-css! []
  (when-let [link (.querySelector js/document "link[href^='style.css']")]
    (set! (.-href link) (str "style.css?t=" (.now js/Date)))))

(defn dispose-scene! []
  (when-let [stop (:stop @runtime)] (stop))
  (when-let [root (:repo-root @runtime)] (clear! root))
  (when-let [renderer (:renderer @runtime)] (.dispose ^js renderer))
  (reset! runtime nil))

(defn install-picking! [canvas camera repo-root]
  (let [raycaster (THREE/Raycaster.)
        pointer (THREE/Vector2.)]
    (.addEventListener
     canvas "click"
     (fn [event]
       (play-sfx! :select)
       (spawn-pulse! (.-clientX event) (.-clientY event))
       (let [rect (.getBoundingClientRect canvas)]
         (set! (.-x pointer) (- (* (/ (- (.-clientX event) (.-left rect))
                                      (.-width rect)) 2) 1))
         (set! (.-y pointer) (- 1 (* (/ (- (.-clientY event) (.-top rect))
                                        (.-height rect)) 2)))
         (.setFromCamera raycaster pointer camera)
         (when-let [hit (first (array-seq
                                (.intersectObjects raycaster
                                                   (.-children repo-root) true)))]
           (if-let [repos (.. ^js hit -object -userData -repos)]
             (when-let [repo (aget repos (.-instanceId hit))]
               (rf/dispatch-sync [:select-repo
                                  (js->clj repo :keywordize-keys true)])
               (apply-state!))
             (when-let [repo (.. ^js hit -object -userData -repo)]
               (rf/dispatch-sync [:select-repo
                                  (js->clj repo :keywordize-keys true)])
               (apply-state!)))))))))

(defn init-scene! []
  (let [canvas (.getElementById js/document "scene")
        scene (three/scene)
        camera (three/perspective-camera (/ (.-innerWidth js/window)
                                             (.-innerHeight js/window)))
        renderer (three/webgl-renderer canvas {:antialias true :alpha false})
        controls (OrbitControls. camera canvas)
        repo-root (three/group)]
    (three/set-clear-color! renderer 0x090611 1)
    (three/set-pixel-ratio! renderer (min 2 (.-devicePixelRatio js/window)))
    (three/set-size! renderer (.-innerWidth js/window) (.-innerHeight js/window))
    (three/set-position! camera 18 22 28)
    (.lookAt ^js camera (THREE/Vector3. 0 0 0))
    (set! (.-enableDamping controls) true)
    (set! (.-dampingFactor controls) 0.07)
    (set! (.-minDistance controls) 5)
    (set! (.-maxDistance controls) 80)
    (.add scene (three/ambient-light 0xbfa8ff 1.4))
    (let [light (three/directional-light 0xffffff 3.2)]
      (three/set-position! light 10 20 12)
      (.add scene light))
    (.add scene (three/grid-helper 80 80 0x39264f 0x191021))
    (.add scene repo-root)
    (reset! runtime {:scene scene :camera camera :renderer renderer
                     :controls controls :repo-root repo-root})
    (install-picking! canvas camera repo-root)
    (.addEventListener js/window "resize"
      (fn []
        (set! (.-aspect camera) (/ (.-innerWidth js/window)
                                   (.-innerHeight js/window)))
        (.updateProjectionMatrix ^js camera)
        (three/set-size! renderer (.-innerWidth js/window)
                         (.-innerHeight js/window))))
    (let [stop
          (three/start-loop!
           renderer scene camera
           (fn [{:keys [time]}]
             (.update ^js controls)
             (doseq [{:keys [object from to phase speed]}
                     (:flow-particles @runtime)]
               (let [progress (mod (+ phase (* time speed)) 1)]
                 (.lerpVectors ^js (.-position ^js object)
                               ^js from ^js to progress)
                 (let [pulse (+ 0.75
                                (* 0.5 (js/Math.sin
                                        (* progress js/Math.PI))))]
                   (three/set-scale! object pulse pulse pulse))))
             (doseq [{:keys [actor head left-arm right-arm left-leg right-leg
                             beacon base phase radius speed working?]}
                     (:actor-animations @runtime)]
               (let [walk (+ phase (* time speed))
                     stride (js/Math.sin (* walk 5))
                     x (+ (.-x base) (* radius (js/Math.cos walk)))
                     z (+ (.-z base) (* radius (js/Math.sin walk)))
                     y (+ (.-y base)
                          (* 0.08 (js/Math.abs
                                   (js/Math.sin (* walk 5)))))]
                 (three/set-position! actor x y z)
                 (set! (.. actor -rotation -y) (- walk (/ js/Math.PI 2)))
                 (set! (.. left-arm -rotation -x) (* 0.65 stride))
                 (set! (.. right-arm -rotation -x) (* -0.65 stride))
                 (set! (.. left-leg -rotation -x) (* -0.55 stride))
                 (set! (.. right-leg -rotation -x) (* 0.55 stride))
                 (set! (.. head -rotation -y)
                       (* 0.18 (js/Math.sin (* time 1.7))))
                 (set! (.. beacon -rotation -z)
                       (+ (* time (if working? 1.8 0.35)) phase))
                 (let [pulse (if working?
                               (+ 1 (* 0.12 (js/Math.sin (* time 4))))
                               0.82)]
                   (three/set-scale! beacon pulse pulse pulse))))))]
      (swap! runtime assoc :stop stop))))

(defn ^:export init []
  (rf/dispatch-sync [:snapshot nil])
  (rf/dispatch-sync [:group-mode :org])
  (rf/dispatch-sync [:activity-agent "all"])
  (mount-shell!)
  (init-scene!)
  (set! (.-tamakiReceive js/window)
        (fn [snapshot]
          (let [value (js->clj snapshot :keywordize-keys true)]
            (index-datoms! value)
            (rf/dispatch-sync [:snapshot value]))
          (apply-state!))))

(defn ^:dev/after-load hot-reload! []
  (refresh-css!)
  (dispose-scene!)
  (mount-shell!)
  (reset! topology-signature nil)
  (init-scene!)
  (apply-state!))

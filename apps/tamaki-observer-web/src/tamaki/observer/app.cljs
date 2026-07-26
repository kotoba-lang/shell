(ns tamaki.observer.app
  (:require ["three" :as THREE]
            ["three/addons/controls/OrbitControls.js" :refer [OrbitControls]]
            [clojure.string :as str]
            [datascript.core :as d]
            [re-frame.core :as rf]
            [org-threejs.core :as three]))

(defonce runtime (atom nil))
(defonce topology-signature (atom nil))
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
(rf/reg-sub :snapshot (fn [db _] (:snapshot db)))
(rf/reg-sub :selected (fn [db _] (:selected db)))
(rf/reg-sub :group-mode (fn [db _] (or (:group-mode db) :org)))

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
  (mapv (fn [[id project model runner issue goal]]
          {:agent.run/id id :agent.run/project project :agent.run/model model
           :agent.run/runner runner :agent.run/goal goal :issue issue})
        (d/q '[:find ?id ?project ?model ?runner ?issue ?goal
               :where [?e :agent/id ?id] [?e :agent/project ?project]
               [?e :agent/model ?model] [?e :agent/runner ?runner]
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

(defn add-project-topologies! [root positions projects]
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
            node (three/mesh
                  (THREE/OctahedronGeometry. 0.28)
                  (three/standard-material
                   {:color status-color :emissive 1.5 :roughness 0.25}))]
        (three/set-position! node x y z)
        (set! (.. node -userData -repo)
              (clj->js {:path (:repo issue) :issue (:rad issue)
                        :project (:id project) :issue-key key}))
        (swap! issue-positions assoc key {:x x :y y :z z})
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
        (.add root line)))))

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
      (add-system-dynamics! repo-root (:system-dynamics snapshot))
      (doseq [[agent-index agent] (map-indexed vector (queried-agents))
              :let [position (get @positions (:agent.run/project agent))]
              :when position]
        (let [y (+ (:height position) 0.7 (* (mod agent-index 3) 0.35))
              node (three/mesh (three/sphere-geometry)
                               (material :active))
              line-geometry (doto (THREE/BufferGeometry.)
                              (.setFromPoints
                               #js [(THREE/Vector3. (:x position)
                                                   (:height position) (:z position))
                                    (THREE/Vector3. (:x position) y (:z position))]))
              line (THREE/Line. line-geometry
                                (THREE/LineBasicMaterial.
                                 #js {:color 0x35ff8a}))]
          (three/set-scale! node 0.48 0.48 0.48)
          (three/set-position! node (:x position) y (:z position))
          (set! (.. node -userData -repo) (clj->js agent))
          (.add repo-root line)
          (.add repo-root node)))
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
        activity (.getElementById js/document "activity")
        model-usage (.getElementById js/document "model-usage")
        dynamics-panel (.getElementById js/document "system-dynamics")
        grouping (.getElementById js/document "grouping")
        repo (or selected active)
        path (or (:path repo) (:agent.run/project repo))
        stat (some #(when (= path (:path %)) %) (:repo-stats snapshot))]
    (set! (.-value grouping) (label group-mode "org"))
    (set! (.-textContent metrics)
          (str (count (visible-repos)) "/" total " repo tiles · "
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
    (set! (.-innerHTML activity)
          (apply str
                 (for [{:keys [at kind run issue patch text]}
                       (take 12 (:activity snapshot))]
                   (str "<div class=\"event\"><time>"
                        (.toLocaleTimeString (js/Date. at))
                        "</time><b>" (label kind "event") "</b><small>"
                        (or text issue patch run "workspace")
                        "</small></div>"))))
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
    (let [{:keys [stocks flows bottleneck failure-pressure backlog-delta
                  throughput]} (:system-dynamics snapshot)]
      (set! (.-innerHTML dynamics-panel)
            (str "<div class=\"dynamics-heading\"><b>System dynamics</b>"
                 "<span>1h flow · bottleneck " bottleneck
                 " · failure " (.toFixed (* 100 (or failure-pressure 0)) 0)
                 "%</span></div>"
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

(defn install-picking! [canvas camera repo-root]
  (let [raycaster (THREE/Raycaster.)
        pointer (THREE/Vector2.)]
    (.addEventListener
     canvas "click"
     (fn [event]
       (let [rect (.getBoundingClientRect canvas)]
         (set! (.-x pointer) (- (* (/ (- (.-clientX event) (.-left rect))
                                      (.-width rect)) 2) 1))
         (set! (.-y pointer) (- 1 (* (/ (- (.-clientY event) (.-top rect))
                                        (.-height rect)) 2)))
         (.setFromCamera raycaster pointer camera)
         (when-let [hit (first (array-seq
                                (.intersectObjects raycaster
                                                   (.-children repo-root) false)))]
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
    (three/start-loop!
     renderer scene camera
     (fn [{:keys [time]}]
       (.update ^js controls)
       (doseq [{:keys [object from to phase speed]}
               (:flow-particles @runtime)]
         (let [progress (mod (+ phase (* time speed)) 1)]
           (.lerpVectors ^js (.-position ^js object)
                         ^js from ^js to progress)
           (let [pulse (+ 0.75 (* 0.5 (js/Math.sin (* progress js/Math.PI))))]
             (three/set-scale! object pulse pulse pulse))))))))

(defn ^:export init []
  (rf/dispatch-sync [:snapshot nil])
  (rf/dispatch-sync [:group-mode :org])
  (init-scene!)
  (.addEventListener
   (.getElementById js/document "grouping") "change"
   (fn [event]
     (rf/dispatch-sync [:group-mode
                        (keyword (.. event -target -value))])
     (reset! topology-signature nil)
     (apply-state!)))
  (set! (.-tamakiReceive js/window)
        (fn [snapshot]
          (let [value (js->clj snapshot :keywordize-keys true)]
            (index-datoms! value)
            (rf/dispatch-sync [:snapshot value]))
          (apply-state!))))

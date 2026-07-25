(ns tamaki.observer.app
  (:require ["three" :as THREE]
            ["three/addons/controls/OrbitControls.js" :refer [OrbitControls]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [org-threejs.core :as three]))

(defonce runtime (atom nil))
(defonce topology-signature (atom nil))

(rf/reg-event-db :snapshot
  (fn [db [_ snapshot]] (assoc db :snapshot snapshot)))
(rf/reg-event-db :select-repo
  (fn [db [_ repo]] (assoc db :selected repo)))
(rf/reg-sub :snapshot (fn [db _] (:snapshot db)))
(rf/reg-sub :selected (fn [db _] (:selected db)))

(def colors
  {:active 0x35ff8a :different 0xff922e :synced 0x208cff
   :remote 0x514b63 :platform 0x28143c :edge 0x48dcff})

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

(defn rebuild-scene! [snapshot]
  (when-let [{:keys [repo-root]} @runtime]
    (clear! repo-root)
    (let [repos (:repos snapshot)
          orgs (mapv :org (:orgs snapshot))
          grouped (group-by :remote repos)
          active-by-path (into {} (map (juxt :path identity) (:active-repos snapshot)))
          active-paths (set (keys active-by-path))
          positions (atom {})]
      (doseq [[org-index org] (map-indexed vector orgs)
              :let [org-repos (vec (get grouped org))
                    index-by-path (into {} (map-indexed
                                            (fn [i repo] [(:path repo) i])
                                            org-repos))
                    columns (max 1 (js/Math.ceil (js/Math.sqrt (count org-repos))))
                    col (mod org-index 3)
                    row (quot org-index 3)
                    origin-x (- (* col 14) 14)
                    origin-z (- (* row 12) 6)
                    extent (+ 1 (* columns 0.22))
                    platform (three/mesh
                              (THREE/BoxGeometry. extent 0.32 extent)
                              (material :platform))]]
        (three/set-position! platform origin-x -0.2 origin-z)
        (.add repo-root platform)
        (doseq [[kind kind-repos]
                (group-by #(status % active-paths) org-repos)
                :let [geometry (THREE/BoxGeometry. 0.16
                                                   (if (= kind :active) 0.72 0.18)
                                                   0.16)
                      mesh (THREE/InstancedMesh. geometry (material kind)
                                                 (count kind-repos))
                      matrix (THREE/Matrix4.)
                      repo-array (array)]]
          (doseq [[instance-id repo] (map-indexed vector kind-repos)
                  :let [repo-index (get index-by-path (:path repo))
                        x (+ origin-x (* (- (mod repo-index columns)
                                             (/ (dec columns) 2)) 0.22))
                        z (+ origin-z (* (- (quot repo-index columns)
                                             (/ (dec columns) 2)) 0.22))
                        y (if (= kind :active) 0.36 0.09)]]
            (.setPosition matrix x y z)
            (.setMatrixAt mesh instance-id matrix)
            (.push repo-array (clj->js (merge repo (get active-by-path (:path repo)))))
            (swap! positions assoc (:path repo) {:x x :z z}))
          (set! (.. mesh -userData -repos) repo-array)
          (set! (.. mesh -instanceMatrix -needsUpdate) true)
          (.add repo-root mesh)))
      (add-dependency-lines! repo-root @positions (:dependencies snapshot)))))

(defn render-overlay! [snapshot selected]
  (let [{:keys [total west github rad]} (:counts snapshot)
        active (first (:active-repos snapshot))
        metrics (.getElementById js/document "metrics")
        details (.getElementById js/document "details")
        repo (or selected active)]
    (set! (.-textContent metrics)
          (str total " repos · WEST " west " · GitHub " github
               " · Radicle " rad " · " (count (:active-repos snapshot)) " LIVE"))
    (set! (.-innerHTML details)
          (if repo
            (str "<strong>" (:path repo) "</strong><br>"
                 "issue " (or (:issue repo) "—") "<br>"
                 "sync " (name (or (:sync repo) :active)) "<br><br>"
                 (when-let [run (first (:runs repo))]
                   (str "agent " (get run :agent.run/id) "<br>"
                        "model " (or (get run :agent.run/model) "default") "<br>"
                        (get run :agent.run/goal))))
            "Select a repository tile"))))

(defn scene-signature [snapshot]
  [(count (:repos snapshot))
   (mapv (juxt :path :issue) (:active-repos snapshot))
   (frequencies (map :sync (:repos snapshot)))
   (:dependencies snapshot)])

(defn apply-state! []
  (let [snapshot @(rf/subscribe [:snapshot])
        selected @(rf/subscribe [:selected])]
    (when snapshot
      (let [signature (scene-signature snapshot)]
        (when (not= signature @topology-signature)
          (reset! topology-signature signature)
          (rebuild-scene! snapshot)))
      (render-overlay! snapshot selected))))

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
           (when-let [repos (.. ^js hit -object -userData -repos)]
             (when-let [repo (aget repos (.-instanceId hit))]
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
    (three/start-loop! renderer scene camera
                       (fn [_] (.update ^js controls)))))

(defn ^:export init []
  (rf/dispatch-sync [:snapshot nil])
  (init-scene!)
  (set! (.-tamakiReceive js/window)
        (fn [snapshot]
          (rf/dispatch-sync [:snapshot
                             (js->clj snapshot :keywordize-keys true)])
          (apply-state!))))

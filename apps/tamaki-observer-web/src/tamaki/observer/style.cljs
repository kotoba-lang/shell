(ns tamaki.observer.style
  (:require-macros [kotoba.css.shadow :refer [css]]))

(def app
  (css {:position :fixed :inset 0 :width "100%" :height "100%"
        :overflow :hidden :background "#090611" :color "#f6f2ff"
        :font "13px -apple-system, sans-serif"}
       ["*" {:box-sizing :border-box}]))

(def scene
  (css {:position :fixed :inset 0 :width "100%" :height "100%"}))

(def effects
  (css {:position :fixed :inset 0 :z-index 1
        :pointer-events :none :overflow :hidden}
       [".pulse" {:position :absolute :width 52 :height 52
                  :border "2px solid #70ffbc" :border-radius "50%"
                  :box-shadow "0 0 28px #4cff91"
                  :animation "tamaki-pulse .7s ease-out forwards"}]))

(def glass
  (css {:position :fixed :z-index 2
        :background "rgba(22,14,35,.72)"
        :border "1px solid rgba(210,170,255,.18)"
        :backdrop-filter "blur(18px)" :border-radius 16
        :box-shadow "0 20px 60px #0008"}))

(def header
  (css {:top 8 :left 8 :right 306 :padding "9px 12px"
        :display :grid :grid-template-columns "auto 1fr auto"
        :grid-template-rows "22px 28px 27px 43px 25px"
        :column-gap 12 :row-gap 4 :overflow :hidden}
       ["h1" {:grid-column 1 :grid-row 1 :font-size 16 :margin 0
              :white-space :nowrap}]
       [".metrics" {:grid-column "2 / 4" :grid-row 1 :align-self :center
                    :color "#bcb2cc" :white-space :nowrap :overflow :hidden
                    :text-overflow :ellipsis :font-size 11}]
       ["label" {:grid-column 1 :grid-row 2 :align-self :center
                 :margin 0 :color "#9f94af" :white-space :nowrap}]
       ["select" {:margin-left 7 :color "#eee" :background "#241733"
                  :border "1px solid #57416e" :border-radius 7
                  :padding "3px 7px"}]
       [".garden-views" {:grid-column 2 :grid-row 2 :display :flex
                         :align-items :center :gap 5 :margin 0}]
       [".garden-views span" {:margin-right 3 :color "#71dda0"
                              :font "10px ui-monospace, monospace"}]
       [".garden-views button" {:color "#c9e9d5" :background "#10251a"
                                :border "1px solid #376a4c"
                                :border-radius 999 :padding "3px 8px"
                                :font-size 10 :cursor :pointer}]
       [".garden-views button:hover" {:color "#07150d"
                                      :background "#70ffac"
                                      :border-color "#70ffac"}]
       [".bonsai-state" {:grid-column "1 / 4" :grid-row 3
                         :display :flex :align-items :center :gap 8
                         :margin 0 :padding "4px 8px"
                         :border "1px solid #d9a15b55" :border-radius 8
                         :background "#28180d88"
                         :font "10px ui-monospace, monospace"}]
       [".bonsai-state b" {:color "#ffc678"}]
       [".bonsai-state span" {:color "#d9c7ac"}]
       [".bonsai-state small" {:max-width 260 :overflow :hidden
                               :text-overflow :ellipsis :white-space :nowrap
                               :color "#ffad6c"}]
       [".model-usage" {:grid-column 3 :grid-row 4 :display :flex :gap 4
                        :margin 0 :min-width 0 :overflow :hidden}]
       [".actor-state" {:grid-column "1 / 3" :grid-row 4 :display :flex :gap 4
                        :margin 0 :min-width 0 :overflow :hidden}]
       [".actor-card" {:flex "1 1 0" :min-width 0 :padding "4px 6px"
                       :border-radius 7 :overflow :hidden
                       :white-space :nowrap :text-overflow :ellipsis
                       :border "1px solid #4cff9155"
                       :background "#10251a99"
                       :font "9px ui-monospace, monospace"}]
       [".actor-card b" {:display :inline :margin-right 5 :color "#77ffad"}]
       [".actor-card .pressure" {:color "#ffb45b"}]
       [".voice-row" {:grid-column 3 :grid-row 2 :display :flex
                      :align-items :center :justify-content :flex-end
                      :gap 5 :margin 0}]
       [".voice-button" {:color "#dfffea" :background "#173d2a"
                         :border "1px solid #48dd86" :border-radius 8
                         :padding "4px 7px" :font-size 10 :cursor :pointer}]
       [".sound-button" {:color "#e8dcff" :background "#2a1b3e"
                         :border "1px solid #8c67bc" :border-radius 8
                         :padding "4px 7px" :font-size 10 :cursor :pointer}]
       [".voice-status" {:display :none}]
       [".usage-card" {:min-width 72 :padding "4px 6px"
                       :border "1px solid #ffffff17" :border-radius 8
                       :background "#09061188"}]
       [".usage-card b" {:display :block :color "#bda7d8"
                         :font "11px ui-monospace, monospace"}]
       [".usage-card span" {:display :block :color "#d8cfdf"
                            :font "9px ui-monospace, monospace" :margin 0}]
       [".usage-card span:last-child" {:display :none}]
       [".usage-card em" {:color "#82778e" :font-style :normal}]
       [".organism-scopes" {:grid-column "1 / 4" :grid-row 5
                            :display :flex :align-items :center :gap 5
                            :overflow-x :auto}]
       [".organism-scopes button"
        {:flex "0 0 auto" :padding "3px 9px" :border-radius 999
         :border "1px solid #47725b" :background "#0c2117"
         :color "#bfe8ce" :font "9px ui-monospace, monospace"
         :cursor :pointer}]
       [".organism-scopes button:first-child"
        {:border-color "#d4b268" :color "#f2d58e" :background "#2b200e"}]
       [".organism-scopes button:hover"
        {:background "#70ffac" :color "#07150d" :border-color "#70ffac"}]))

(def inspector
  (css {:right 8 :top 8 :bottom 8 :width 290
        :overflow :hidden :padding 12}
       ["h2" {:font-size 13 :margin "0 0 6px"}]
       [".details" {:max-height 125 :overflow-y :auto :color "#c8bfd5"
                    :font-size 11 :line-height 1.4 :word-break :break-word}]
       [".result-panel" {:display :grid :gap 5 :margin "0 0 10px"}]
       [".result-chain" {:padding 7 :border "1px solid #ffffff14"
                         :border-radius 8 :background "#08050e99"}]
       [".result-chain small" {:display :block :color "#8f849c"
                               :overflow :hidden :text-overflow :ellipsis
                               :white-space :nowrap :margin-bottom 5}]
       [".result-chain div" {:display :flex :align-items :center
                             :gap 3 :overflow-x :auto}]
       [".result-node" {:flex "0 0 auto" :padding "2px 5px"
                        :border-radius 5 :font "8px ui-monospace, monospace"
                        :color "#eee" :background "#241733"}]
       [".result-node.source" {:color "#63dfff"}]
       [".result-node.radicle" {:color "#dd86ff"}]
       [".result-node.github" {:color "#fff"}]
       [".result-node.review" {:color "#70ffac"}]
       [".result-node.merge" {:color "#5aa9ff"}]
       [".result-arrow" {:color "#5e536b"}]
       [".activity-title" {:margin-top 9}]
       [".activity-filters" {:display :flex :gap 5 :overflow-x :auto
                             :padding-bottom 8 :margin-bottom 2}]
       [".activity-filters button" {:flex "0 0 auto" :cursor :pointer
                                    :color "#b9aec7" :background "#140c20"
                                    :border "1px solid #473459"
                                    :border-radius 999 :padding "4px 8px"
                                    :font "10px ui-monospace, monospace"}]
       [".activity-filters button.selected"
        {:color "#07150d" :background "#70ffac" :border-color "#70ffac"
         :box-shadow "0 0 12px #42f58d55"}]
       [".event" {:display :grid :grid-template-columns "58px 1fr"
                  :gap "2px 6px" :padding "5px 0"
                  :border-top "1px solid #ffffff12"}]
       [".event time" {:color "#81778e"
                       :font "11px ui-monospace, monospace"}]
       [".event b" {:color "#79ffa8"
                    :font "11px ui-monospace, monospace"}]
       [".event-heading" {:display :flex :align-items :center
                          :justify-content :space-between :gap 6}]
       [".event .stream" {:color "#73bfff" :border "1px solid #3998db55"
                          :border-radius 999 :padding "1px 5px"
                          :font "9px ui-monospace, monospace"}]
       [".event small" {:grid-column 2 :color "#9f94af" :overflow :hidden
                        :text-overflow :ellipsis :white-space :nowrap}]))

(def legend
  (css {:left 8 :bottom 8 :width 304 :padding "7px 10px"
        :font-size 10 :color "#aaa0b8"}
       [".live" {:color "#4cff91"}]
       [".sync" {:color "#309bff"}]
       [".diff" {:color "#ff9d2e"}]
       [".loop" {:color "#c956ff"}]))

(def dynamics
  (css {:left 320 :right 306 :bottom 8 :height 112
        :width :auto :min-width 0 :transform :none
        :padding "8px 10px" :overflow :hidden}
       [".dynamics-heading" {:display :flex :align-items :baseline
                             :justify-content :space-between :gap 12}]
       [".dynamics-heading b" {:font-size 13}]
       [".dynamics-heading span" {:color "#91859f"
                                  :font "10px ui-monospace, monospace"}]
       [".business-control" {:display :flex :justify-content :space-between
                             :gap 10 :margin-top 5
                             :font "10px ui-monospace, monospace"}]
       [".business-control b" {:color "#73f4a1"}]
       [".business-control span" {:color "#bfb4cd"}]
       [".business-control .pressure" {:color "#ffae52"}]
       [".stock-row" {:display :grid
                      :grid-template-columns "repeat(auto-fit, minmax(92px, 1fr))"
                      :gap 5 :margin-top 5}]
       [".stock-card" {:position :relative :padding "6px 8px"
                       :border "1px solid color-mix(in srgb, var(--stock) 55%, transparent)"
                       :border-radius 8
                       :background "linear-gradient(180deg, color-mix(in srgb, var(--stock) 18%, transparent), #08050e99)"
                       :overflow :hidden}]
       [".stock-card::after" {:content "\"\"" :position :absolute
                              :left 0 :right 0 :bottom 0 :height 3
                              :background "var(--stock)"
                              :box-shadow "0 0 12px var(--stock)"}]
       [".stock-card.bottleneck" {:box-shadow
                                  "inset 0 0 0 1px #ffbb58, 0 0 12px #ff9f3333"}]
       [".stock-card small" {:display :block :color "#bfb4cd" :font-size 9}]
       [".stock-card strong" {:font "17px ui-monospace, monospace"
                              :color "#fff" :margin-right 4}]
       [".stock-card em" {:color "#8d8299"
                          :font "9px ui-monospace, monospace"
                          :font-style :normal}]
       [".flow-row" {:display :flex :gap 8 :margin-top 4 :color "#94899f"
                     :font "9px ui-monospace, monospace"
                     :white-space :nowrap}]
       [".flow-row b" {:color "#73f4a1"}]
       [".flow-row .pressure" {:color "#ffae52"}]
       [".flow-row .relief" {:color "#62e6b1"}]))

(def finance
  (css [".finance-dashboard"
        {:position :fixed :z-index 3 :left 8 :right 306 :top 198 :bottom 8
         :padding 18 :overflow-y :auto :border-radius 16
         :background "linear-gradient(145deg, rgba(8,18,14,.97), rgba(19,11,28,.97))"
         :border "1px solid #7ee6ab33" :box-shadow "0 24px 80px #000b"
         :color "#f2fff7"}]
       [".finance-dashboard.hidden" {:display :none}]
       [".finance-title" {:display :flex :align-items :end
                          :justify-content :space-between
                          :border-bottom "1px solid #ffffff18"
                          :padding-bottom 12}]
       [".finance-title small" {:color "#67e89d"
                                :font "10px ui-monospace, monospace"
                                :letter-spacing ".15em"}]
       [".finance-title h2" {:margin "3px 0 0" :font-size 24}]
       [".finance-period" {:color "#9c91aa"
                           :font "11px ui-monospace, monospace"}]
       [".finance-kpis" {:display :grid
                         :grid-template-columns "repeat(4, minmax(0, 1fr))"
                         :gap 8 :margin "14px 0"}]
       [".finance-kpi" {:padding "12px 14px" :border-radius 10
                        :background "#ffffff08"
                        :border "1px solid #ffffff13"}]
       [".finance-kpi small" {:display :block :color "#9e94aa"
                              :font-size 10 :margin-bottom 5}]
       [".finance-kpi strong" {:font "20px ui-monospace, monospace"}]
       [".finance-kpi strong.warning" {:color "#ffad62"}]
       [".finance-segments" {:display :grid
                             :grid-template-columns "repeat(3, minmax(0, 1fr))"
                             :gap 8 :margin-bottom 10}]
       [".finance-segment" {:display :grid
                            :grid-template-columns "1fr auto"
                            :align-items :baseline :padding "7px 10px"
                            :border-radius 9 :background "#071b1299"
                            :border "1px solid #70e99f2b"}]
       [".finance-segment span" {:color "#70e99f"
                                 :font "10px ui-monospace, monospace"}]
       [".finance-segment strong" {:font "12px ui-monospace, monospace"}]
       [".finance-segment small" {:grid-column "1 / 3" :color "#81788b"
                                  :font "8px ui-monospace, monospace"}]
       [".finance-statements" {:display :grid
                               :grid-template-columns "repeat(3, minmax(0, 1fr))"
                               :gap 10}]
       [".finance-card" {:padding 14 :border-radius 12
                         :background "#090c0bbb"
                         :border "1px solid #ffffff14"}]
       [".finance-card h3" {:margin "0 0 10px" :color "#77eba5"
                            :font "13px ui-monospace, monospace"}]
       [".finance-line" {:display :flex :justify-content :space-between
                         :gap 10 :padding "8px 0"
                         :border-top "1px solid #ffffff0d"
                         :color "#aaa1b2"}]
       [".finance-line strong" {:color "#f6fff9"
                                :font "12px ui-monospace, monospace"}]
       [".finance-line.total" {:border-top "1px solid #74eaa45c"
                               :color "#d7f8e2"}]
       [".finance-line.total strong" {:color "#7dffae"}]
       [".finance-empty" {:margin-top 12 :padding 10 :border-radius 8
                          :color "#ffbd72" :background "#37210f99"
                          :font "11px ui-monospace, monospace"}]))

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
  (css {:top 16 :left 16 :padding "12px 18px"}
       ["h1" {:font-size 18 :margin "0 0 5px"}]
       [".metrics" {:color "#bcb2cc"}]
       ["label" {:display :block :margin-top 9 :color "#9f94af"}]
       ["select" {:margin-left 7 :color "#eee" :background "#241733"
                  :border "1px solid #57416e" :border-radius 7
                  :padding "3px 7px"}]
       [".model-usage" {:display :flex :gap 6 :margin-top 10}]
       [".actor-state" {:display :flex :gap 6 :margin-top 7}]
       [".actor-card" {:padding "5px 8px" :border-radius 8
                       :border "1px solid #4cff9155"
                       :background "#10251a99"
                       :font "10px ui-monospace, monospace"}]
       [".actor-card b" {:display :block :color "#77ffad"}]
       [".actor-card .pressure" {:color "#ffb45b"}]
       [".voice-row" {:display :flex :align-items :center :gap 8
                      :margin-top 9}]
       [".voice-button" {:color "#dfffea" :background "#173d2a"
                         :border "1px solid #48dd86" :border-radius 8
                         :padding "5px 9px" :cursor :pointer}]
       [".sound-button" {:color "#e8dcff" :background "#2a1b3e"
                         :border "1px solid #8c67bc" :border-radius 8
                         :padding "5px 9px" :cursor :pointer}]
       [".voice-status" {:max-width 310 :overflow :hidden
                         :text-overflow :ellipsis :white-space :nowrap
                         :color "#a99db7" :font "10px ui-monospace, monospace"}]
       [".usage-card" {:min-width 92 :padding "6px 8px"
                       :border "1px solid #ffffff17" :border-radius 8
                       :background "#09061188"}]
       [".usage-card b" {:display :block :color "#bda7d8"
                         :font "11px ui-monospace, monospace"}]
       [".usage-card span" {:display :block :color "#d8cfdf"
                            :font "10px ui-monospace, monospace"
                            :margin-top 2}]
       [".usage-card em" {:color "#82778e" :font-style :normal}]))

(def inspector
  (css {:right 16 :top 16 :width 290 :max-height "calc(100% - 32px)"
        :overflow :auto :padding 16}
       ["h2" {:font-size 15 :margin "0 0 10px"}]
       [".details" {:color "#c8bfd5" :line-height 1.55
                    :word-break :break-word}]
       [".result-panel" {:display :grid :gap 7 :margin "0 0 18px"}]
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
       [".activity-title" {:margin-top 20}]
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
       [".event" {:display :grid :grid-template-columns "64px 1fr"
                  :gap "2px 8px" :padding "7px 0"
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
  (css {:left 16 :bottom 16 :padding "10px 14px" :color "#aaa0b8"}
       [".live" {:color "#4cff91"}]
       [".sync" {:color "#309bff"}]
       [".diff" {:color "#ff9d2e"}]
       [".loop" {:color "#c956ff"}]))

(def dynamics
  (css {:left "50%" :bottom 16
        :width "min(650px, calc(100% - 650px))" :min-width 460
        :transform "translateX(-50%)" :padding "11px 13px"}
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
                      :gap 7 :margin-top 8}]
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
       [".flow-row" {:display :flex :gap 11 :margin-top 7 :color "#94899f"
                     :font "9px ui-monospace, monospace"
                     :white-space :nowrap}]
       [".flow-row b" {:color "#73f4a1"}]
       [".flow-row .pressure" {:color "#ffae52"}]
       [".flow-row .relief" {:color "#62e6b1"}]))

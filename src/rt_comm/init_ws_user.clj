(ns rt-comm.init-ws-user
  (:require [rt-comm.incoming.connect-auth :as conn-auth] 

            [rt-comm.incoming.ws-user-pulsar    :as ws-user-pulsar]
            [rt-comm.incoming.ws-user-coreasync :as ws-user-coreasync]
            [rt-comm.incoming.ws-user-manifold  :as ws-user-manifold]

            [rt-comm.incoming.stateless-transform :as stateless-transf]

            [rt-comm.utils.utils :as u :refer [cond= valp]]
            [rt-comm.utils.async :as au]

            [manifold.deferred :as d]
            [manifold.stream :as s]
            [clojure.core.async :as a] ;; for testing only ->

            [co.paralleluniverse.pulsar.core :as p :refer [rcv channel sfn defsfn snd join fiber spawn-fiber sleep]]
            [co.paralleluniverse.pulsar.actors :refer [! spawn]]

            [rt-comm.components.event-queue :as eq] ;; testing only!

            [taoensso.timbre :refer [debug info error spy]]))




(defn load-user-data [m]
  (->> (u/load-config "dev/resources/user-data.edn")
       (assoc m :user-data)))


(defsfn connect-auth-init! [ws-user-args timeouts]
  "Suspendible fn running connect- and auth process, conditionally calling init-ws-user!"
  (some-> ws-user-args 
          (conn-auth/connect-process timeouts) ;; wait for connection and assoc user-socket
          (cond= :server :aleph #(assoc % :ch-incoming (-> % :user-socket s/->source))) ;; with aleph the user-socket is also the ch-incoming
          load-user-data
          (conn-auth/auth-process timeouts) ;; returns augmented init-ws-user-args or nil

          #_init-ws-user!))

;; TEST-CODE: Immutant
;; (do 
;; (def !calls (atom []))
;; (def args {:server-snd-fn   (fn [ch msg] (swap! !calls conj msg))
;;            :server-close-fn (fn [ch] (swap! !calls conj "closed!"))})
;;
;; (def ch-incoming (a/chan))
;; (def on-open-user-socket (promise))
;; (def user-socket (a/chan))
;;
;; (def fb (fiber (connect-auth-init! (merge args {:ch-incoming          ch-incoming
;;                                                 :on-open-user-socket  on-open-user-socket
;;                                                 :server :immutant}) 
;;                                    3000)))
;; )
;;
;; (deliver on-open-user-socket user-socket) ;; connect event
;; (a/>!! ch-incoming {:cmd [:auth {:user-id "pete" :pw "abc"}]}) ;; immutant
;; ;; (s/put! user-socket {:cmd [:auth {:user-id "pete" :pw "abc"}]}) ;; aleph
;; (:auth-result (join fb))
;; (deref !calls)

;; TEST-CODE: Aleph
;; (do 
;; (def !calls (atom []))
;; (def args {:server-snd-fn   (fn [ch msg] (swap! !calls conj msg))
;;            :server-close-fn (fn [ch] (swap! !calls conj "closed!"))})
;;
;; (def on-open-user-socket (d/deferred))
;; (def user-socket (s/stream))
;;
;; (def fb (fiber (connect-auth-init! (merge args {:on-open-user-socket  on-open-user-socket
;;                                                 :server :aleph}) 
;;                                    3000)))
;; )
;;
;; (deliver on-open-user-socket user-socket) ;; connect event
;; (s/put! user-socket {:cmd [:auth {:user-id "pete" :pw "abc"}]}) ;; immutant
;; ;; (s/put! user-socket {:cmd [:auth {:user-id "pete" :pw "abc"}]}) ;; aleph
;; (:auth-result (join fb))
;; (deref !calls)


(defn init-ws-user! [{:keys [user-socket ch-incoming event-queue] :as args}]

  (let [in-tx           (-> (select-keys args [:user-id :allowed-actns])
                            stateless-transf/incoming-tx) 

        incom-tx-stream (case (au/ch-type ch-incoming)
                          :pulsar    (au/transform-pch ch-incoming in-tx) 
                          :coreasync (au/transform-ch  ch-incoming in-tx)
                          :manifold  (s/transform in-tx ch-incoming))

        incoming-actor (case (au/ch-type ch-incoming)
                          :pulsar    (ws-user-pulsar/incoming-ws-user-actor 
                                       incom-tx-stream #(! event-queue %) (select-keys args [:batch-sample-intv])) 
                          :coreasync (ws-user-coreasync/incoming-ws-user-actor 
                                       incom-tx-stream #(! event-queue %) (select-keys args [:batch-sample-intv])) 
                          :manifold  (ws-user-manifold/incoming-ws-user-actor 
                                       incom-tx-stream #(! event-queue %) (select-keys args [:batch-sample-intv])))

        outgoing-actor nil]

    (swap! (:ws-conns args) conj {:user-id        (:user-id args)
                                  :socket         user-socket ;; debug only?!
                                  :incoming-actor incoming-actor
                                  :outgoing-actor outgoing-actor})))

;; TEST CODE:
;; (def ev-queue (spawn eq/server-actor [] 10))
;; (def user-socket (s/stream 4))
;; (def user-socket (a/chan 4))
;; (def user-socket (p/channel 4))
;;
;; (def ws-conns (init-ws-user! {:user-socket user-socket
;;                               :event-queue ev-queue
;;                               :user-id "pete"
;;                               :allowed-actns [:aa :bb]
;;                               :batch-sample-intv 0
;;                               :ws-conns (atom [])
;;                               }))
;;
;; (def cmd-ch (:incoming-actor (first ws-conns)))
;;
;; (s/put! user-socket [{:actn :aea :idx 12} 
;;                      {:actn :aa :idx 13} 
;;                      {:idx 14}])
;; (s/put! user-socket [{:actn :ada :idx 12} 
;;                      {:idx 14}])
;;
;; (a/>!! user-socket [{:actn :aea :idx 12} 
;;                     {:actn :aa :idx 13} 
;;                     {:idx 14}])
;; (a/>!! user-socket [{:actn :ada :idx 12} 
;;                     {:idx 14}])
;;
;;
;; (s/put! cmd-ch [:fixed-recip-chs #{:ach :bch}])
;; (s/put! cmd-ch [:fixed-recip-chs nil])
;; (s/put! cmd-ch :pause-rcv-overflow)
;; (s/put! cmd-ch :resume-rcv)
;;
;; (a/>!! cmd-ch [:fixed-recip-chs #{:ach :bch}])
;; (a/>!! cmd-ch [:fixed-recip-chs nil])
;; (a/>!! cmd-ch :pause-rcv-overflow)
;; (a/>!! cmd-ch :resume-rcv)
;;
;;
;; (def res (promise))
;; (s/put! cmd-ch [:debug-prc-cnt res])
;; (deref res)
;;
;; (s/put! cmd-ch [:shut-down "Buye!"])
;;
;; (info "------")




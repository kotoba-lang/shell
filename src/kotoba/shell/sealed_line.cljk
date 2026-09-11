(ns kotoba.shell.sealed-line
  "AES-256-GCM envelope for newline-oriented local stores. Key custody and
  persistence are host/application responsibilities."
  #?(:clj (:import [java.security SecureRandom]
                   [javax.crypto Cipher]
                   [javax.crypto.spec GCMParameterSpec SecretKeySpec]
                   [java.util Base64]))
  #?(:cljs (:require ["crypto" :as crypto])))

(def version 1)

#?(:clj
   (do
     (defn random-key []
       (let [bytes (byte-array 32)] (.nextBytes (SecureRandom.) bytes) bytes))
     (defn- b64 [bytes] (.encodeToString (Base64/getEncoder) bytes))
     (defn- unb64 [s] (.decode (Base64/getDecoder) s))
     (defn seal [key plaintext aad]
       (let [nonce (random-key)
             nonce (java.util.Arrays/copyOf nonce 12)
             cipher (Cipher/getInstance "AES/GCM/NoPadding")]
         (.init cipher Cipher/ENCRYPT_MODE (SecretKeySpec. key "AES") (GCMParameterSpec. 128 nonce))
         (.updateAAD cipher (.getBytes (str aad) "UTF-8"))
         {:sealed/version version :sealed/alg :aes-256-gcm
          :sealed/nonce (b64 nonce)
          :sealed/ciphertext (b64 (.doFinal cipher (.getBytes (str plaintext) "UTF-8")))}))
     (defn open [key envelope aad]
       (when-not (and (= version (:sealed/version envelope))
                      (= :aes-256-gcm (:sealed/alg envelope)))
         (throw (ex-info "unsupported sealed-line envelope" {})))
       (let [cipher (Cipher/getInstance "AES/GCM/NoPadding")]
         (.init cipher Cipher/DECRYPT_MODE (SecretKeySpec. key "AES")
                (GCMParameterSpec. 128 (unb64 (:sealed/nonce envelope))))
         (.updateAAD cipher (.getBytes (str aad) "UTF-8"))
         (String. (.doFinal cipher (unb64 (:sealed/ciphertext envelope))) "UTF-8")))))

#?(:cljs
   (do
     (defn random-key [] (crypto/randomBytes 32))
     (defn seal [key plaintext aad]
       (let [nonce (crypto/randomBytes 12)
             cipher (crypto/createCipheriv "aes-256-gcm" key nonce)
             _ (.setAAD cipher (.from js/Buffer (str aad) "utf8"))
             ciphertext (.concat js/Buffer #js [(.update cipher (str plaintext) "utf8") (.final cipher)])
             combined (.concat js/Buffer #js [ciphertext (.getAuthTag cipher)])]
         {:sealed/version version :sealed/alg :aes-256-gcm
          :sealed/nonce (.toString nonce "base64")
          :sealed/ciphertext (.toString combined "base64")}))
     (defn open [key envelope aad]
       (when-not (and (= version (:sealed/version envelope))
                      (= :aes-256-gcm (:sealed/alg envelope)))
         (throw (js/Error. "unsupported sealed-line envelope")))
       (let [nonce (.from js/Buffer (:sealed/nonce envelope) "base64")
             combined (.from js/Buffer (:sealed/ciphertext envelope) "base64")
             split (- (.-length combined) 16)
             ciphertext (.subarray combined 0 split)
             tag (.subarray combined split)
             decipher (crypto/createDecipheriv "aes-256-gcm" key nonce)]
         (.setAAD decipher (.from js/Buffer (str aad) "utf8"))
         (.setAuthTag decipher tag)
         (.toString (.concat js/Buffer #js [(.update decipher ciphertext) (.final decipher)]) "utf8")))))

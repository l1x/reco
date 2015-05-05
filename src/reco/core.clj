;;Copyright 2015 Istvan Szukacs

;;Licensed under the Apache License, Version 2.0 (the "License");
;;you may not use this file except in compliance with the License.
;;You may obtain a copy of the License at

;;    http://www.apache.org/licenses/LICENSE-2.0

;;Unless required by applicable law or agreed to in writing, software
;;distributed under the License is distributed on an "AS IS" BASIS,
;;WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;See the License for the specific language governing permissions and
;;limitations under the License

(ns reco.core
  (:require
    [clojure.edn            :as edn ]
    [clojure.tools.logging  :as log ]
    [clojure.tools.cli      :as cli ]
    [clojure.data.xml       :as xml ]
    [clojure.java.io        :as io  ]
    [clojure.data.json      :as json]
    )
  (:import 
    [java.io File]
    [java.util UUID]
    [clojure.lang PersistentHashMap PersistentArrayMap])
  (:gen-class))

;; HELPERS

;; Read config

;;https://github.com/l1x/shovel/blob/master/src/shovel/core.clj
;;this is considered defensing programming, but it is intentional
;;supplying an arbitrary bad input should not cause this function to 
;;explode, in fact it should just return an error
(defn read-file
  "Returns {:ok string } or {:error...}"
  [^String file]
  (try
    (cond
      (.isFile (File. file))
        {:ok (slurp file) }                         ; if .isFile is true {:ok string}
      :else
        (throw (Exception. "Input is not a file"))) ;the input is not a file, throw exception
  (catch Exception e
    {:error "Exception" :fn "read-file" :exception (.getMessage e) }))) ; catch all exceptions

;;Parsing a string to Clojure data structures the safe way
;;aka what could possibly go wrong dealing with a random
;;user controlled string
(defn parse-edn-string
  "Returns the Clojure data structure representation of s"
  [s]
  (try
    {:ok (clojure.edn/read-string s)}
  (catch Exception e
    {:error "Exception" :fn "parse-config" :exception (.getMessage e)})))

;This function wraps the read-file and the parse-edn-string
;so that it only return {:ok ... } or {:error ...} 
(defn read-config
  "Returns the Clojure data structure version of the config file"
  [file]
  (let 
    [ file-string (read-file file) ]
    (cond
      (contains? file-string :ok)
        ;this return the {:ok} or {:error} from parse-edn-string
        (parse-edn-string (file-string :ok))
      :else
        ;the read-file operation returned an error
        file-string)))

(defn uuid
  "Returns a new java.util.UUID as string" 
  []
  (str (UUID/randomUUID)))

;; CLI

(def cli-options
  ;; An option with a required argument
  [["-p" "--port PORT" "Port number"
    :default 80
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ;; A non-idempotent option
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :assoc-fn (fn [m k _] (update-in m [k] inc))]
   ;; A boolean option defaulting to nil
   ["-h" "--help"]])


(defn exit [n] 
  (log/info "init :: stop")
  (System/exit n))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; OPS 

(defn config-ok [config]
  (log/info "config [ok]") 
  (log/info config))

(defn config-err [config]
  (log/error "config [error]") 
  (log/error config)
  (exit 1))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; MAIN

(defn -main [& args]
  ""
  (log/info (cli/parse-opts args cli-options))
  (let [ ^PersistentHashMap config    (read-config "conf/app.edn") 
                            cli-opts  (cli/parse-opts args cli-options)]
    ;; INIT
    (log/info "init :: start")
    (log/info "checking config...")
    (cond 
      (contains? config :ok)
        (config-ok config)
      :else
        ;; exit 1 here
        (config-err config))

    (let [  
          
          
          file-path     (get-in config [:ok :reco :file-path]) 
          input-xml     (io/reader file-path)
          xml-elements  (xml/parse input-xml)

          ;; OPTIMIZE THIS
      
          process-first (->> xml-elements 
                          :content 
                          first 
                          :content
                          (filter #(= (:tag %) :dict))
                          first
                          :content
                          (filter #(= (:tag %) :dict))
                          (map :content))

          process-second  (map 
                            #(map first %) 
                            (for [record process-first] (map :content record)))

          process-third   (for [record process-second]
                            (map (fn [l]
                                     ;return []
                                     [ (keyword (clojure.string/replace (.toLowerCase (first l)) #" " "_"))
                                       (second l)  ])
                                 ;partition each list element to pairs
                                 (partition 2 record)))

        process-fourth  (for [record process-third]
                          (into {} (for [element record] {(first element) (second element)})))
        ;; OPTIMIZE END
                             
          ]

      (doseq [x process-fourth] (spit (str (uuid) ".json") (json/write-str x)))

    ;; end main
)))
;;END

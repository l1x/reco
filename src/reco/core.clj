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
    [org.httpkit.client     :as http]
    )
  (:import 
    [java.io File]
    [java.util UUID]
    [clojure.lang PersistentHashMap PersistentArrayMap])
  (:gen-class))

;; HELPERS
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

;; DEV


(def to-be-replaced 
  [[#"\ " "_"] [#"\?" "_"] [#"\%" "_"]])

(defn safe-name
  ""
  [name to-be-replaced] 
  (reduce (fn [acc [a b]] (clojure.string/replace acc a b)) (.toLowerCase name) to-be-replaced))



;https://api.discogs.com/database/search?release_title=nevermind&artist=nirvana&per_page=1&page=1&token=SgpxjKTqGpjWEVyjQQuQUKSoRucECiyKxjjgYzIc

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; OPS 

(defn exit [n] 
  (log/info "init :: stop")
  (System/exit n))

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
          make-records (for [record process-fourth] 
                         (into [] [
                          (:artist        record "Unknown")
                          (:album_artist  record (:artist record))
                          (:album         record "Unknown")
                          (:name          record "Unknown")
                          (:track_number  record 0) ] ))

        albums (into #{} (for [record process-fourth] 
                           [  (:album_artist  record (:artist record))
                              (:album         record "Unknown")         ])) ]
        
      
      ;; OPTIMIZE END

        (doseq [album albums] 
                           (let [ url (str "https://ajax.googleapis.com/ajax/services/search/web?v=1.0&q=" 
                                           "%22Michal+Menert%22+%22Dreaming+Of+A+Bigger+Life%22"
                                            "+site:bandcamp.com") ]
                                     (clojure.pprint/pprint url))))))

      ;(doseq [y (for [x process-fourth] {(str (uuid) ".json") x})] (clojure.pprint/pp y))
;     (doseq [json-doc (for [x process-fourth] 
;                        {(str (uuid) ".json") x})] 
;       ;printing each json doc
;       (clojure.pprint/pprint json-doc))
;        (clojure.pprint/pprint make-records)
    ;; end main
;;END

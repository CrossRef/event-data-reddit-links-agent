(ns event-data-reddit-links-agent.core
  (:require [org.crossref.event-data-agent-framework.core :as c]
            [event-data-common.status :as status]
            [crossref.util.doi :as cr-doi]
            [clojure.tools.logging :as log]
            [clojure.java.io :refer [reader]]
            [clojure.data.json :as json]
            [clj-time.coerce :as coerce]
            [clj-time.core :as clj-time]
            [throttler.core :refer [throttle-fn]]
            [clj-http.client :as client]
            [config.core :refer [env]]
            [robert.bruce :refer [try-try-again]]
            [clj-time.format :as clj-time-format])
  (:import [java.util UUID]
           [java.net URL]
           [org.apache.commons.codec.digest DigestUtils]
           [org.apache.commons.lang3 StringEscapeUtils])
  (:gen-class))

(defn remove-utm
  "Remove tracking links, e.g. in http://www.npr.org/sections/13.7/2017/03/27/521620741/a-day-in-the-life-of-an-academic-mom?utm_source=facebook.com&utm_medium=social&utm_campaign=npr&utm_term=nprnews&utm_content=20170327"
  [url]
  (let [[base query-string] (.split url "\\?")
        args (when query-string (.split query-string "&"))
        filtered-args (when args (remove #(.startsWith % "utm") args))
        combined-args (when filtered-args (clojure.string/join "&" filtered-args))]
    (if (empty? combined-args)
      base
      (str base "?" combined-args))))

(def source-token "93df90e8-1881-40fc-b19d-49d78cc9ee24")
(def user-agent "CrossrefEventDataBot (eventdata@crossref.org) (by /u/crossref-bot labs@crossref.org)")
(def license "https://creativecommons.org/publicdomain/zero/1.0/")
(def version (System/getProperty "event-data-reddit-links-agent.version"))

(def date-format
  (:date-time-no-ms clj-time-format/formatters))

; Auth
(def reddit-token (atom nil))
(defn fetch-reddit-token
  "Fetch a new Reddit token."
  []
  (status/send! "reddit-links-agent" "reddit" "authenticate" 1)
  (let [response (client/post
                    "https://www.reddit.com/api/v1/access_token"
                     {:as :text
                      :headers {"User-Agent" user-agent}
                      :form-params {"grant_type" "password"
                                    "username" (:reddit-app-name env)
                                    "password" (:reddit-password env)}
                      :basic-auth [(:reddit-client env) (:reddit-secret env)]
                      :throw-exceptions false})
        token (when-let [body (:body response)]
                (->
                  (json/read-str body :key-fn keyword)
                  :access_token))]
    token))

(defn check-reddit-token
  "Check the current Reddit token. Return if it works."
  []
  (let [token @reddit-token
        result (client/get "https://oauth.reddit.com/api/v1/me"
                         {:headers {"User-Agent" user-agent
                                    "Authorization" (str "bearer " token)}
                          :throw-exceptions false})]
      (if-not (= (:status result) 200)
        (log/error "Couldn't verify OAuth Token" token " got " result)
        token)))

(defn get-reddit-token
  "Return valid token. Fetch new one if necessary."
  []
  (log/info "Checking token")
  (let [valid-token (check-reddit-token)]
    (log/info "Token result" valid-token)
   (if valid-token
    valid-token
    (do
      (reset! reddit-token (fetch-reddit-token))
      (check-reddit-token)))))


(def interested-kinds
  #{"t3"}) ; Link

(def uninterested-hostnames
  "Ignore links on these domains because they're conversations on reddit. We're looking for external links."
  #{"reddit.com" "www.reddit.com"})

(def domain-set
  "Ignore links on these domains because they're publisher pages. We don't want to visit those."
  (atom #{}))

(defn api-item-to-action
  [item]
  (let [occurred-at-iso8601 (clj-time-format/unparse date-format (coerce/from-long (* 1000 (long (-> item :data :created_utc)))))
        link (-> item :data :url)
        unescaped-link (StringEscapeUtils/unescapeHtml4 link)
        cleaned-link (remove-utm unescaped-link)
        host (try (.getHost (new URL unescaped-link)) (catch Exception e nil))]

    ; We only care about things that are links and that are links to external sites.
    ; Reddit discussions are a seprate thing.
    (when (and (interested-kinds (:kind item))
               (not (uninterested-hostnames host))
               (not (@domain-set host)))

    {:id (DigestUtils/sha1Hex ^String cleaned-link)
     :url cleaned-link
     :relation-type-id "discusses"
     :occurred-at occurred-at-iso8601
     :subj {}
     :observations [{:type :content-url :input-url cleaned-link :sensitive true}]})))

; API
(defn parse-page
  "Parse response JSON to a page of Actions."
  [url json-data]
  (let [parsed (json/read-str json-data :key-fn keyword)]
    {:url url
     :extra {
      :after (-> parsed :data :after)
      :before (-> parsed :data :before)}
     ; parse returns nil for links we don't want. Don't include null actions.
     :actions (keep api-item-to-action (-> parsed :data :children))}))

(def auth-sleep-duration
  "Back off for a bit if we face an auth problem"
  ; 5 mins
  (* 1000 60 5))

(defn fetch-page
  "Fetch the API result, return a page of Actions."
  [subreddit after-token]
  (status/send! "reddit-links-agent" "reddit" "fetch-page" 1)
  (let [url (str "https://oauth.reddit.com" subreddit "/new.json?sort=new&after=" after-token)]
    (log/info "Fetch" url)
    ; If the API returns an error
    (try
      (try-try-again
        {:sleep 30000 :tries 10}
        #(let [result (client/get url {:headers {"User-Agent" user-agent
                                                 "Authorization" (str "bearer " (get-reddit-token))}})]
          (log/info "Fetched" url)

          (condp = (:status result)
            200 (parse-page url (:body result))
            404 {:url url :actions [] :extra {:after nil :before nil :error "Result not found"}}
            401 (do
                  (log/error "Unauthorized to access" url)
                  (log/error "Body of error response:" (:body url))
                  (log/info "Taking a nap...")
                  (Thread/sleep auth-sleep-duration)
                  (log/info "Woken up!")
                  (throw (new Exception "Unauthorized")))
            (do
              (log/error "Unexpected status code" (:status result) "from" url)
              (log/error "Body of error response:" (:body url))
              (throw (new Exception "Unexpected status"))))))

      (catch Exception ex (do
        (log/error "Error fetching" url)
        (log/error "Exception:" ex)
        {:url url :actions [] :extra {:after nil :before nil :error "Failed to retrieve page"}})))))


(def fetch-page-throttled (throttle-fn fetch-page 20 :minute))

(defn fetch-pages
  "Lazy sequence of pages for the subreddit."
  ([subreddit]
    (fetch-pages subreddit nil))

  ([subreddit after-token]
    (let [result (fetch-page-throttled subreddit after-token)
          ; Token for next page. If this is null then we've reached the end of the iteration.
          after-token (-> result :extra :after)]

      (if after-token
        (lazy-seq (cons result (fetch-pages subreddit after-token)))
        [result]))))

(defn all-action-dates-after?
  [date page]
  (let [dates (map #(-> % :occurred-at coerce/from-string) (:actions page))]
    (every? #(clj-time/after? % date) dates)))

(defn fetch-parsed-pages-after
  "Fetch seq parsed pages of Actions until all actions on the page happened before the given time."
  [subreddit date]
  (let [pages (fetch-pages subreddit)]
    (take-while (partial all-action-dates-after? date) pages)))

(defn check-all-subreddits
  "Check all subreddits for unseen links."
  [artifacts callback]
  (log/info "Start crawl all Domains on Reddit at" (str (clj-time/now)))
  (status/send! "reddit-links-agent" "process" "scan-subreddits" 1)
  (let [[domain-list-url domain-list] (get artifacts "domain-list")
        [subreddit-list-url subreddit-list] (get artifacts "subreddit-list")
        this-domain-set (set (clojure.string/split domain-list #"\n"))
        subreddits (set (clojure.string/split subreddit-list #"\n"))
        num-subreddits (count subreddits)
        counter (atom 0)
        ; Take 48 hours worth of pages to make sure we cover everything. The Percolator will dedupe.
        cutoff-date (-> 48 clj-time/hours clj-time/ago)]
    (reset! domain-set this-domain-set)
    (doseq [subreddit subreddits]
      (swap! counter inc)
      (log/info "Query subreddit:" subreddit @counter "/" num-subreddits " = " (int (* 100 (/ @counter num-subreddits))) "%")
      (let [pages (fetch-parsed-pages-after subreddit cutoff-date)
            package {:source-token source-token
                     :source-id "reddit-links"
                     :license license
                     :agent {:version version :artifacts {:domain-set-artifact-version domain-list-url :subreddit-set-artifact-version subreddit-list-url}}
                     :extra {:cutoff-date (str cutoff-date) :queried-subreddit subreddit}
                     :pages pages}]
        (log/info "Sending package...")
        (callback package))))
  (log/info "Finished scan."))

(def agent-definition
  {:agent-name "reddit-links-agent"
   :version version
   :jwt (:reddit-links-jwt env)
   :schedule [{:name "check-all-subreddits"
              :seconds 86400 ; wait 1 day between runs
              :fixed-delay true
              :fun check-all-subreddits
              :required-artifacts ["domain-list" "subreddit-list"]}]
   :runners []})

(defn -main [& args]
  (c/run agent-definition))

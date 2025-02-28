#!/usr/bin/env bb

 (require '[babashka.http-client :as http]
          '[clojure.java.io :as io]
          '[clojure.string :as str]
          '[babashka.fs :as fs]
          '[babashka.process :refer [process check]])

;; Constants
(def config-file (str (System/getenv "HOME") "/.config/vpn_config.ini"))
(def cookie-file "/tmp/cookievpn")
(def vpnc-script (str (System/getProperty "user.dir") "/vpnc-script"))
(def prefix-file (str (System/getenv "HOME") "/.mobilepassprefix"))



;; Helpers
(defn log [msg] (println "\n🔔" msg))
(defn separator [] (println "\n" (apply str (repeat 40 "━")) "\n"))

(defn get-ini-value [section key]
  (let [content (slurp config-file)
        section-pattern (re-pattern (str "(?s)\\[" section "\\](.*?)(?=\\[|$)"))
        section-content (or (second (re-find section-pattern content)) "")
        key-pattern (re-pattern (str "(?m)^" key "\\s*=\\s*(.*)$"))]
    (or (second (re-find key-pattern section-content)) "")))

(defn cleanup [pid]
  (separator)
  (log "🧹 Cleaning up...")
  (when pid
    (try (check (process ["sudo" "kill" "-SIGTERM" pid]))
         (catch Exception _)))
  (run! fs/delete-if-exists [cookie-file "/tmp/vpn_routes"]))

(defn save-cookies [cookies]
  (with-open [w (io/writer cookie-file)]
    (.write w "# Netscape HTTP Cookie File\n")
    (doseq [[name {:keys [value domain path secure expires]}] cookies]
      (.write w (str/join "\t" [(or domain ".")
                                "TRUE"
                                (or path "/")
                                (if secure "TRUE" "FALSE")
                                (or expires "0")
                                (str name)
                                (str value)
                                "\n"])))))

(defn auth-vpn [{:keys [url realm user-agent credentials]}]
  (prn "auth-vpn" url)
  (try
    (let [response (http/post url
                              {:headers {"User-Agent" user-agent
                                         "Accept" (get-ini-value "vpn" "accept")
                                         "Accept-Language" (get-ini-value "vpn" "accept_language")
                                         "Accept-Encoding" (get-ini-value "vpn" "accept_encoding")
                                         "Content-Type" "application/x-www-form-urlencoded"
                                         "Origin" (get-ini-value "vpn" "origin")
                                         "Referer" url}
                               :form-params (merge credentials
                                                   {"tz_offset" "60"
                                                    "clientMAC" ""
                                                    "realm" realm
                                                    "btnSubmit" "Se connecter"})
                               :follow-redirects true})
          cookies (get-in response [:headers "set-cookie"])]

      (when cookies
        (save-cookies {"DSID" {:value cookies
                               :domain "."
                               :path "/"}}))

      {:success (< (:status response) 400)
       :cookies cookies
       :status (:status response)})
    (catch Exception e
      {:success false :error (.getMessage e)})))


(auth-vpn {:url (get-ini-value "vpn" "url")
           :realm (get-ini-value "vpn" "realm")
           :user-agent (get-ini-value "vpn" "user_agent")
           :credentials {"username" "PVQW2943"
                         "password" "17320556284980"}})


(defn get-dsid []
  (when (fs/exists? cookie-file)
    (some->> (slurp cookie-file)
             str/split-lines
             (some #(when (str/includes? % "DSID") %))
             (re-find #"DSID\s+(\S+)")
             second)))

(defn main [& args]
  (let [config {:vpn-host (get-ini-value "vpn" "host")
                :vpn-url (get-ini-value "vpn" "url")
                :vpn-realm (get-ini-value "vpn" "realm")
                :vpn-user-agent (get-ini-value "vpn" "user_agent")
                :ssh-host (get-ini-value "ssh_tunnel" "host")
                :ssh-ns (get-ini-value "ssh_tunnel" "nameserver")
                :routes (str/split (get-ini-value "routes" "routes_to_replace") #",")}]

    (try
      (println "\n🚀 Starting VPN Connection Script")
      (separator)

      ;; Save routes
      (spit "/tmp/vpn_routes" (str/join "\n" (:routes config)))
      ;;(fs/set-posix-file-permissions "/tmp/vpn_routes" "rw-------")

      ;; Authentication
      (when-not (fs/exists? cookie-file)
        (log "🔑 Authentication required")
        (let [cuid (str/upper-case (System/getenv "USER"))
              _ (print "Mobile pass token: ")
              token (read-line)
              prefix (when (fs/exists? prefix-file) (str/trim (slurp prefix-file)))
              auth-result (auth-vpn {:url (:vpn-url config)
                                     :realm (:vpn-realm config)
                                     :user-agent (:vpn-user-agent config)
                                     :credentials {"username" cuid
                                                   "password" (str prefix token)}})]
          (when-not (:success auth-result)
            (throw (ex-info "Authentication failed" auth-result)))))

      ;; Get DSID and start VPN
      (if-let [dsid (get-dsid)]
        (do
          (separator)
          (log "🔌 Starting openconnect")
          (let [vpn-process (process ["sudo" "openconnect" "-b" "--no-dtls"
                                      "--protocol=nc" (:vpn-host config)
                                      "--force-dpd" "5" "--disable-ipv6"
                                      "--timestamp" "--cookie" (str "DSID=" dsid)
                                      "--script" vpnc-script]
                                     {:out :string})
                _ (Thread/sleep 5000)
                #_pid #_(-> (process ["pgrep" "openconnect"]) check :out str/trim)]

            #_(log (str "📍 OpenConnect PID: " pid))
            (separator)

            ;; Start sshuttle if not disabled
            (when-not (= (first args) "noshuttle")
              (log "🚇 Starting sshuttle")
              (let [networks-include (str/split (get-ini-value "ssh_tunnel" "networks_include") #",")
                    networks-exclude (str/split (get-ini-value "ssh_tunnel" "networks_exclude") #",")
                    network-args (concat
                                  (map str/trim networks-include)
                                  (mapcat #(vector "-x" (str/trim %)) networks-exclude))]
                (-> (process (concat ["sshuttle" "-v" "-N"
                                      "--ns-hosts" (:ssh-ns config)
                                      "-r" (str (System/getenv "USER") "@" (:ssh-host config))]
                                     network-args))
                    check)))

            ;; Keep script running
            (while true (Thread/sleep 1000))))
        (throw (ex-info "No DSID cookie found" {})))

      (catch Exception e
        (log (str "❌ Error: " (ex-message e)))
        ;; (cleanup (some-> (process ["pgrep" "openconnect"]) check :out str/trim))
        (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (main *command-line-args*))



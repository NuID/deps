(ns nuid.deps
  (:require
   #?@(:clj
       [[clj-jgit.porcelain :as git]
        [clojure.java.shell :as sh]
        [clojure.java.io :as io]
        [clojure.edn :as edn]
        [clojure.set :as set]])))

(def home (System/getProperty "user.home"))
(def library-directory (str home "/dev/nuid"))
(def git-url-root "git@github.com:nuid")

(defn dep [library & [{:keys [local/root git/url]
                       :or {root (str library-directory "/" (name library))
                            url (str git-url-root "/" (name library) ".git")}}]]
  {library {:local/root root :git/url url}})

(def default-deps
  (merge
   {'nuid/create-registration
    {:local/root (str library-directory "/front-end/lambda/create-registration")
     :git/url (str git-url-root "/front-end.git")}
    'nuid/find-txid
    {:local/root (str library-directory "/front-end/lambda/find-txid")
     :git/url (str git-url-root "/front-end.git")}
    'nuid/initialize
    {:local/root (str library-directory "/front-end/lambda/initialize")
     :git/url (str git-url-root "/front-end.git")}
    'nuid/check-finalization
    {:local/root (str library-directory "/ethereum-registration/api/lambda/check-finalization")
     :git/url (str git-url-root "/ethereum-registration.git")}
    'nuid/register
    {:local/root (str library-directory "/ethereum-registration/api/lambda/register")
     :git/url (str git-url-root "/ethereum-registration.git")}
    'nuid/process-finalizations
    {:local/root (str library-directory "/ethereum-registration/lambda/process-finalizations")
     :git/url (str git-url-root "/ethereum-registration.git")}
    'nuid/process-registrations
    {:local/root (str library-directory "/ethereum-registration/lambda/process-registrations")
     :git/url (str git-url-root "/ethereum-registration.git")}
    'nuid/record-registration
    {:local/root (str library-directory "/ethereum-registration/lambda/record-registration")
     :git/url (str git-url-root "/ethereum-registration.git")}}
   (dep 'nuid/cryptography)
   (dep 'nuid/inspector)
   (dep 'nuid/ethereum)
   (dep 'nuid/browser)
   (dep 'nuid/transit)
   (dep 'nuid/utils)
   (dep 'nuid/specs)
   (dep 'nuid/site)
   (dep 'nuid/repl)
   (dep 'nuid/deps)
   (dep 'nuid/zka)
   (dep 'nuid/ecc)
   (dep 'nuid/bn)))

(defn deps-path [deps lib] (str (:local/root (lib deps)) "/deps.edn"))

(defn read-edn [f]
  (with-open [r (io/reader f)]
    (let [r (java.io.PushbackReader. r)]
      (edn/read r))))

(defn read-deps
  ([deps lib] (read-deps (deps-path deps lib)))
  ([path] (read-edn path)))

(defn compute-updates
  ([deps lib] (compute-updates deps #{} lib))
  ([deps acc lib]
   (let [ds (read-deps deps lib)
         eds (set (mapcat (comp keys :extra-deps second) (:aliases ds)))
         all-ds (set/union (set (keys (:deps ds))) eds)
         all (set (keys deps))
         i (set/intersection all-ds all)
         d (set/difference i acc)
         u (set/union i acc)]
     (if (empty? d)
       acc
       (reduce (fn [a l] (set/union a (compute-updates deps a l))) u d)))))

(defn write-deps! [path deps]
  (with-open [w (io/writer path)]
    (binding [*print-namespace-maps* false
              *print-length* false
              *out* w]
      (clojure.pprint/pprint deps))))

(defn extra-deps-xf [coords [k v]]
  {k (if-let [a (:extra-deps v)]
       (let [b (merge a (select-keys coords (keys a)))]
         (assoc v :extra-deps b))
       v)})

(defn localize-deps [deps ds]
  (let [as-local-coord (fn [[k v]] [k (select-keys v [:local/root])])
        local-coords (into {} (map as-local-coord) deps)
        updated-deps (merge (:deps ds) (select-keys local-coords (keys (:deps ds))))
        updated-aliases (into {} (map (partial extra-deps-xf local-coords)) (:aliases ds))]
    (assoc ds :deps updated-deps :aliases updated-aliases)))

(defn localize! [deps lib]
  (let [updates (conj (compute-updates deps lib) lib)]
    (doseq [u updates]
      (let [ds (localize-deps deps (read-deps deps u))
            path (deps-path deps u)]
        (write-deps! path ds)))))

(defn get-repo [f]
  (try (git/load-repo f)
       (catch Exception e
         (if-let [p (.getParent (io/file f))]
           (get-repo p)
           (throw e)))))

(defn clean? [repo]
  (let [repo (if (string? repo) (get-repo repo) repo)]
    (every? empty? (vals (git/git-status repo)))))

(defn rev [repo]
  (let [repo (if (string? repo) (get-repo repo) repo)]
    (.getName (first (git/git-log repo)))))

(defn add-commit-messages [deps lib]
  (let [updates (conj (compute-updates deps lib) lib)
        f (fn [acc k]
            (prn k 'commit (symbol "message:"))
            (let [in (read-line)
                  in (if (empty? in) (str "Updated " k " dependencies") in)]
              (prn in)
              (assoc-in acc [k :message] in)))]
    (reduce f deps updates)))

(defn add-commit-push! [path message]
  (prn 'pushing (symbol path))
  (sh/sh "git" "add" "." :dir path)
  (sh/sh "git" "commit" "-m" message :dir path)
  (sh/sh "git" "push" :dir path)
  (rev path))

(defn dep-sha! [computed-deps lib]
  (let [path (:local/root (lib computed-deps))
        v (if (clean? path)
            (rev path)
            (add-commit-push! path (:message (lib computed-deps))))]
    (assoc-in computed-deps [lib :sha] v)))

(defn update-deps [deps ds]
  (let [as-git-coord (fn [[k v]] [k (select-keys v [:git/url :sha])])
        git-coords (into {} (map as-git-coord) deps)
        updated-deps (merge (:deps ds) (select-keys git-coords (keys (:deps ds))))
        updated-aliases (into {} (map (partial extra-deps-xf git-coords)) (:aliases ds))]
    (assoc ds :deps updated-deps :aliases updated-aliases)))

(defn update-deps! [deps lib]
  (let [ds (read-deps deps lib)
        all-libs (set (keys deps))
        updated-libs (set (map first (filter (comp :sha second) deps)))
        ds-libs (set (keys (:deps ds)))
        extra-ds-libs (set (mapcat (comp keys :extra-deps second) (:aliases ds)))
        all-ds-libs (set/union ds-libs extra-ds-libs)
        libs-to-update (set/difference (set/intersection all-ds-libs all-libs) updated-libs)]
    (if (empty? libs-to-update)
      (let [path (deps-path deps lib)
            uds (update-deps deps ds)]
        (write-deps! path uds)
        (dep-sha! deps lib))
      (let [c (reduce (fn [_ l] (update-deps! deps l)) {} libs-to-update)]
        (update-deps! c lib)))))

(defn update! [deps lib]
  (-> (add-commit-messages deps lib)
      (update-deps! lib)))

(defn add-dep [deps dep] (update deps :deps merge dep))

(defn add-dep!
  ([deps lib dep] (add-dep! (deps-path deps lib) dep))
  ([path dep] (let [deps (add-dep (read-deps path) dep)]
                (write-deps! path deps))))

(defn remove-dep [deps lib] (update deps :deps dissoc lib))

(defn remove-dep!
  ([deps lib rmlib] (remove-dep! (deps-path deps lib) rmlib))
  ([path lib] (let [deps (remove-dep (read-deps path) lib)]
                (write-deps! path deps))))

(comment

  (compute-updates default-deps 'nuid/repl)
  (localize! default-deps 'nuid/repl)
  (update! default-deps 'nuid/repl)

  )

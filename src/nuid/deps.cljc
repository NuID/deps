(ns nuid.deps
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.set :as set]
   #?@(:clj
       [[clj-jgit.porcelain :as git]
        [clojure.java.shell :as sh]
        [clojure.java.io :as io]
        [clojure.edn :as edn]])))

(defn read-edn [f]
  (with-open [r (io/reader f)]
    (let [r (java.io.PushbackReader. r)]
      (edn/read r))))

(defn write-edn! [path data]
  (with-open [w (io/writer path)]
    (binding [*print-namespace-maps* false
              *print-length* false
              *out* w]
      (clojure.pprint/pprint data))))

(defn parse-lib [{r :repository/root g :git/root u :git/url} lib]
  [lib {:git/url (if g (str g "/" lib ".git") u)
        :local/root (str r "/" lib)}])

(defn parse-repository [{:keys [repository/libs] :as repository}]
  (into {} (map (partial parse-lib repository)) libs))

(defn parse-config [config]
  (->> (config :deps/repositories)
       (map parse-repository)
       (apply merge (dissoc config :deps/repositories))))

(defn read-config
  [& [{:keys [path] :or {path (str (System/getProperty "user.dir") "/deps.config.edn")}}]]
  (parse-config (read-edn path)))

(defn deps-path [deps lib] (str (:local/root (lib deps)) "/deps.edn"))

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

(defn extra-deps-xf [coords [k v]]
  [k (if-let [a (:extra-deps v)]
       (let [b (merge a (select-keys coords (keys a)))]
         (assoc v :extra-deps b))
       v)])

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
        (write-edn! path ds)))))

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
  (let [p (sh/sh "git" "push" :dir path)]
    (clojure.pprint/pprint p))
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
        (write-edn! path uds)
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
                (write-edn! path deps))))

(defn remove-dep [deps lib] (update deps :deps dissoc lib))

(defn remove-dep!
  ([deps lib rmlib] (remove-dep! (deps-path deps lib) rmlib))
  ([path lib] (let [deps (remove-dep (read-deps path) lib)]
                (write-edn! path deps))))

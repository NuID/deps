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

(defn deps-path [config lib] (str (:local/root (config lib)) "/deps.edn"))

(defn read-deps
  ([config lib] (read-deps (deps-path config lib)))
  ([path] (read-edn path)))

(defn compute-updates
  ([config lib] (compute-updates config #{} lib))
  ([config acc lib]
   (let [deps (read-deps config lib)
         extra-deps (set (mapcat (comp keys :extra-deps second) (:aliases deps)))
         all-deps (set/union (set (keys (:deps deps))) extra-deps)
         all (set (keys config))
         i (set/intersection all-deps all)
         d (set/difference i acc)
         u (set/union i acc)]
     (if (empty? d)
       acc
       (reduce (fn [a l] (set/union a (compute-updates config a l))) u d)))))

(defn extra-deps-xf [coords [k v]]
  [k (if-let [a (:extra-deps v)]
       (let [b (merge a (select-keys coords (keys a)))]
         (assoc v :extra-deps b))
       v)])

(defn localize-deps [config deps]
  (let [as-local-coord (fn [[k v]] [k (select-keys v [:local/root])])
        local-coords (into {} (map as-local-coord) config)
        updated-deps (merge (:deps deps) (select-keys local-coords (keys (:deps deps))))
        updated-aliases (into {} (map (partial extra-deps-xf local-coords)) (:aliases deps))]
    (assoc deps :deps updated-deps :aliases updated-aliases)))

(defn localize! [config lib]
  (let [updates (conj (compute-updates config lib) lib)]
    (doseq [u updates]
      (let [deps (localize-deps config (read-deps config u))
            path (deps-path config u)]
        (write-edn! path deps)))))

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

(defn add-commit-messages [config lib]
  (let [updates (conj (compute-updates config lib) lib)
        f (fn [acc k]
            (prn k 'commit (symbol "message:"))
            (let [in (read-line)
                  in (if (empty? in) (str "Updated " k " dependencies") in)]
              (prn in)
              (assoc-in acc [k :message] in)))]
    (reduce f config updates)))

(defn add-commit-push! [path message]
  (prn 'pushing (symbol path))
  (sh/sh "git" "add" "." :dir path)
  (sh/sh "git" "commit" "-m" message :dir path)
  (let [p (sh/sh "git" "push" :dir path)]
    (clojure.pprint/pprint p))
  (rev path))

(defn dep-sha! [config lib]
  (let [path (:local/root (lib config))
        v (if (clean? path)
            (rev path)
            (add-commit-push! path (:message (lib config))))]
    (assoc-in config [lib :sha] v)))

(defn update-deps [config deps]
  (let [as-git-coord (fn [[k v]] [k (select-keys v [:git/url :sha])])
        git-coords (into {} (map as-git-coord) config)
        updated-deps (merge (:deps deps) (select-keys git-coords (keys (:deps deps))))
        updated-aliases (into {} (map (partial extra-deps-xf git-coords)) (:aliases deps))]
    (assoc deps :deps updated-deps :aliases updated-aliases)))

(defn update-deps! [config lib]
  (let [deps (read-deps config lib)
        all-libs (set (keys config))
        updated-libs (set (map first (filter (comp :sha second) config)))
        deps-libs (set (keys (:deps deps)))
        extra-deps-libs (set (mapcat (comp keys :extra-deps second) (:aliases deps)))
        all-deps-libs (set/union deps-libs extra-deps-libs)
        libs-to-update (set/difference (set/intersection all-deps-libs all-libs) updated-libs)]
    (if (empty? libs-to-update)
      (let [updated-deps (update-deps config deps)
            path (deps-path config lib)]
        (write-edn! path updated-deps)
        (dep-sha! config lib))
      (let [c (reduce (fn [_ l] (update-deps! config l)) {} libs-to-update)]
        (update-deps! c lib)))))

(defn update! [config lib]
  (-> (add-commit-messages config lib)
      (update-deps! lib)))

(defn add-dep [deps dep] (update deps :deps merge dep))

(defn add-dep!
  ([config lib dep] (add-dep! (deps-path config lib) dep))
  ([path dep] (let [deps (add-dep (read-deps path) dep)]
                (write-edn! path deps))))

(defn remove-dep [deps lib] (update deps :deps dissoc lib))

(defn remove-dep!
  ([config lib rmlib] (remove-dep! (deps-path config lib) rmlib))
  ([path lib] (let [deps (remove-dep (read-deps path) lib)]
                (write-edn! path deps))))

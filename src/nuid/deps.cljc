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
  [lib {:git/url    (if g
                      (str g
                           (if (str/starts-with? g "git@")
                             ":"
                             "/")
                           lib
                           ".git")
                      u)
        :local/root (if g
                      (str r "/" (last (str/split (str lib) #"/")))
                      (str r "/" lib))}])

(defn parse-repository [{:keys [repository/libs] :as repository}]
  (into {} (map (partial parse-lib repository)) libs))

(defn parse-config [config]
  (->> (config :deps/repositories)
       (map parse-repository)
       (apply merge (dissoc config :deps/repositories))))

(defn read-config
  ([] (read-config (str (System/getProperty "user.dir") "/deps.config.edn")))
  ([path] (parse-config (read-edn path))))

(defn deps-path [config lib] (str (:local/root (config lib)) "/deps.edn"))

(defn read-deps
  ([config lib] (read-deps (deps-path config lib)))
  ([path] (read-edn path)))

(defn compute-updates
  "Computes the set of libraries that need to be updated as part of updating `lib`."
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

(defn localize!
  "Recursively converts `:git/url` coordinates in `lib` and its dependencies
  defined in `config` to `:local/root` coordinates for local development."
  [config lib]
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

(defn commit! [path message]
  (sh/sh "git" "add" "." :dir path)
  (sh/sh "git" "commit" "-m" message :dir path))

(defn push! [path]
  (let [p (sh/sh "git" "push" :dir path)]
    (clojure.pprint/pprint p)))

(defn dep-sha! [config lib push]
  (let [path (:local/root (lib config))
        v (if (clean? path)
            (rev path)
            (do (commit! path (:message (lib config)))
                (if push
                  (push! path))
                (rev path)))]
    (assoc-in config [lib :sha] v)))

(defn update-deps [config deps]
  (let [as-git-coord (fn [[k v]] [k (select-keys v [:git/url :sha])])
        git-coords (into {} (map as-git-coord) config)
        updated-deps (merge (:deps deps) (select-keys git-coords (keys (:deps deps))))
        updated-aliases (into {} (map (partial extra-deps-xf git-coords)) (:aliases deps))]
    (assoc deps :deps updated-deps :aliases updated-aliases)))

(defn update-deps! [config lib push]
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
        (dep-sha! config lib push))
      (let [c (reduce (fn [_ l] (update-deps! config l push)) {} libs-to-update)]
        (update-deps! c lib push)))))

(defn update!
  "First prompts the user for the commit messages to be applied. Then recursively
  updates `:local/root` coordinates in `lib` and its dependencies defined in
  `config` to `:git/url` coordinates at the most recent revision (`:sha`)."
  ([config lib]
   (update! config lib false))
  ([config lib push]
   (-> (add-commit-messages config lib)
       (update-deps! lib push))))

(defn add-dep [deps dep] (update deps :deps merge dep))

(defn add-dep!
  "Adds `dep` to the `deps.edn` file for `lib` as defined in `config`, or at `path`."
  ([config lib dep] (add-dep! (deps-path config lib) dep))
  ([path dep] (let [deps (add-dep (read-deps path) dep)]
                (write-edn! path deps))))

(defn remove-dep [deps lib] (update deps :deps dissoc lib))

(defn remove-dep!
  "Removes `rmlib` from the `deps.edn` file for `lib` as defined in `config`, or
  `lib` from the `deps.edn` file at `path`."
  ([config lib rmlib] (remove-dep! (deps-path config lib) rmlib))
  ([path lib] (let [deps (remove-dep (read-deps path) lib)]
                (write-edn! path deps))))

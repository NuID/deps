# nuid.deps

Dependency management facilities for [`tools.deps`](https://clojure.org/guides/deps_and_cli) workflows.

## Motivation

[`tools.deps`](https://clojure.org/guides/deps_and_cli) allows for the specification of dependencies located at various "coordinates" (currently local, maven, and git). The `tools.deps` ecosystem allows developers to isolate functionality into small libraries while maintaining benefits commonly associated with the monorepo: code-sharing, circular-dependency avoidance, and rapid iteration on dependent modules. It really is [dependency heaven](https://www.youtube.com/watch?v=sStlTye-Kjk).

In rapidly developing dependent libraries with many `:git/url` coordinates, I've found it desirable to `localize!` the git dependencies of a project that are changing frequently, i.e. convert them to `:local/root` coordinates so that no deployment step is needed to affect the changes. Once the changes have been finalized, the `localize!`'d coordinates must be `update!`'d back to `:git/url` coordinates with the appropriate (new) revision as the `:sha`, which must be done in accordance with the dependency tree (depth-first). This library automates those two tasks.

## Notes

`tools.deps` is alpha software, and `nuid.deps` is really just a few helper funtions on top of it. There are rough edges.

Dependency tree construction and traversal is pretty naive. `nuid.deps` is entirely unoptimized.

## Requirements

[`jvm`](https://www.java.com/en/download/), [`clj`](https://clojure.org/guides/getting_started)

## From Clojure

### tools.deps:

`{nuid/deps {:git/url "https://github.com/nuid/deps" :sha "..."}`

### usage:

```
$ clj
=> (require '[nuid.deps :as deps])
=> (def config (deps/read-config)) ;; tries to read ./deps.config.edn by default

=> (deps/localize! config 'some.lib/in.config)

... make changes to some.lib/in.config and its dependencies that may also be specified in config ...

=> (deps/update! config 'some.lib/in.config)

... nuid.deps is reading user input to add commit messages to each updated dependency
... nuid.deps is committing and pushing ...

=> ;; some.lib/in.config and all of its dependencies that also appear in config
   ;; now reference the most up-to-date git revisions in their own deps.edn

=> (def another-config (deps/read-config "/Users/example/dev/lib/another.deps.config.edn"))
=> ...
```

## `deps.config.edn`

The dependencies to be managed by `nuid.deps` must be specified in a configuration map, which most `nuid.deps` functions take as their first argument. I imagine this map will typically be generated from an `edn` configuration file (either local or shared) in combination with the `nuid.deps/read-config` function. The default path for `nuid.deps/read-config` is `./deps.config.edn`, i.e. a valid `edn` file named `deps.config.edn` in the current working directory. See [`deps.config.example.edn`](https://github.com/NuID/deps/blob/master/deps.config.example.edn) in the project's root and [syntax](#syntax) for more information on the configuration file.

The configuration map is necessary because the vanilla `deps.edn` file must only reference one coordinate per dependency—`tools.deps` would have no way of disambiguating the intent if e.g. `:local/root`, `:git/url`, and `:git/sha` were all specified for a given library in its `deps.edn` entry. However, in order to `localize!` and `update!` dependencies, there must be a durable source from which we can retrieve the information necessary for the toggle.

The configuration map holds two pieces of information about each library:

* First, it holds the `:local/root` which allows `nuid.deps` functions to locate libraries on the local filesystem in order to recursively `localize!` the input library's dependencies. When dependencies are `localize!`'d, it is easy to iterate without a deployment step necessary to affect changes; feedback is essentially immediate.

* Second, the configuration map holds a library's `:git/url`, i.e. the repository that `nuid.deps` will push changes to while walking the dependency tree to `update!` each dependency in an appropriate order.

I've found the configuration file generally useful within the `tools.deps` ecosystem for reading and automated manipulation of `deps.edn` files. Tangentially, using this library this causes `deps.edn` files to tend autopretty: both `localize!` and `update!` cause every `deps.edn` they touch to be written via `pprint`. I find this favorable, but perhaps it's not for everyone.

### syntax:

The syntax of the `deps.config.edn` file is standard `edn`, and it looks somewhat similar to the `:deps` map of a  `deps.edn` file. However, it specifies both `:local/root` and `:git/url` for each specified library:

```
{some/lib {:local/root "..." :git/url "..."}
 ...}
```

There is a more concise way to specify groups (loosely "deps/repositories", which is probably poor nomenclature given the context) of related libraries as well:

```
{'some/lib {:local/root "..." :git/url "..."},
...
:deps/repositories
[{:repository/root "/Users/example/dev"
:git/root "https://github.com"
:repository/libs
[repo1/lib1
repo1/lib2]}
...]}
```

This will cause `nuid.deps` to read locally from: `/Users/example/dev/repo1/lib<1,2,...>`,

and to push to: `https://github.com/repo1/lib<1,2,...>`.

Correct...the `:deps/repositories` key basically just allows for the exploitation of naming conventions.

There are some other usage patterns as well, e.g. using `git@github.com` as the `:git/root` to push to (potentially private) repositories using `ssh`, and specifying a "deps/repository" within a single git repository by using `:git/url` instead of `:git/root`.

## `tools.deps` alias

`nuid.deps` also adds to the classpath a forked and regularly rebased branch of `tools.deps` with [`add-lib`](http://insideclojure.org/2018/05/04/add-lib/) included (at least until this feature makes it into `master`). It can be useful to add `nuid.deps` as an `:extra-dependency` in a standalone `alias` in a project's `deps.edn` for ultradynamic `lib` and `dep` management, e.g.:

```
:aliases
{:repl
 {:extra-deps
  {nuid/deps
   {:git/url "https://github.com/nuid/deps.git",
    :sha "..."}}}}
```

Now, when started with `clj -A:repl` or similar, dependencies of the project can be `localize!`'d for rapid local development iteration, and subsequently `update!`'d to reflect the latest revisions in their `deps.edn` files, all without leaving the REPL.<sup>1<sup>]

Further, the REPL's classpath can be changed dynamically using `tools.deps/add-lib`. If the added library becomes more than a transient trial, it can be persisted as a dependency in `deps.edn` using `nuid.deps/add-dep!`. This functionality is experimental, and the API isn't the most empowering quite yet.

<sup>1<sup>After an `update!`, it would require a REPL refresh to clone any new revisions into `gitlibs` and add them to the classpath. However the new revisions will be identical to the local coordinates already on the classpath after the original (one-time) call to  `localize!` and subsequent calls to `(require '[...] :reload-all)`, so the REPL refresh is essentially unnecessary.

## `git`

This library shells out to `git`, which means it will inherit configuration from the environment. The git interactions are altogether primitive.

Beyond allowing for the specification of commit messages, `nuid.deps` does very little in terms of specifying (or allowing the specification of) git commands—**it will push to the currently checked out branch** according to the environment configuration of `git commit` and `git push`. This includes `git hooks`, commit signing, etc..

## Contributing

PRs would be most welcome! At the top of my list is `spec`ing the library, making the API more friendly in map, reduce, etc. for bulk operations, and revisiting the `git` interactions entirely.

Install [`git-hooks`](https://github.com/icefox/git-hooks) and fire away.

### formatting:

```
$ clojure -A:cljfmt            # check
$ clojure -A:cljfmt:cljfmt/fix # fix
```

### dependencies:

```
## check
$ clojure -A:depot

## update
$ clojure -A:depot:depot/update
```

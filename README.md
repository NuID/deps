# nuid.deps

Dependency management facilities for [`tools.deps`](https://clojure.org/guides/deps_and_cli) workflows.

## Motivation

[`tools.deps`](https://clojure.org/guides/deps_and_cli) allows for the specification of dependencies at various types of "coordinates" (currently local, maven, and git). It allows developers to isolate functionality into small, modular libraries, but maintain the benefits commonly associated with the monorepo: code-sharing, circular-dependency avoidance, and frictionless local development of sibling modules. It really is [dependency heaven](https://www.youtube.com/watch?v=sStlTye-Kjk).

In rapidly developing sibling libraries with many `:git/url` coordinates, I've found it desirable to `localize!` the git dependencies of a project that are changing frequently, i.e. convert them to a  `:local/root` coordinate. Once changes have been made, those local coordinates must be `update!`d to the appropriate git revision, which must be done in accordance with the dependency tree. This library automates those two tasks.

## Notes

`tools.deps` is alpha software, and `nuid.deps` is really just a few helper funtions on top of it. There are rough edges.

`nuid.deps` requires a map (e.g. read from `deps.config.edn`) that specifies how to find dependencies locally and where to push them when they are ready to be `update!`d. This allows for variation between development environments (e.g. not everyone uses `~/dev/`). See `deps.config.example.edn` and [below](#depsconfigedn) for more information.

The dependency tree construction and traversal is pretty naive. `nuid.deps` is not optimized.

## Requirements

[`jvm`](https://www.java.com/en/download/), [`clj`](https://clojure.org/guides/getting_started)

## From Clojure

#### tools.deps:

`{nuid/deps {:git/url "https://github.com/nuid/deps" :sha "..."}`

#### usage:

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

The dependencies to be managed by this tool are specified in a map that most of the functions take as their first parameter. I imagine this map will typically be generated from an `edn` configuration file. The default path for `nuid.deps/read-config` is `./deps.config.edn`.

The reason this configuration map is necessary is because `deps.edn` can only reference one coordinate type at a time—it would have no way of disambiguating the intent e.g. if all of `:local/root`, `:git/url`, and `:git/sha` were specified for a given library.

The configuration map (potentially read from `deps.config.edn`, or whatever you decide to name it) allows `nuid.deps` functions to find a library locally in order to `localize!` its dependents, and also defines where `nuid.deps` will push changes in an `update!`.

I've found this file generally useful within the `tools.deps` ecosystem for reading and automated manipulation of `deps.edn` files.

Tangentially, using this library this causes `deps.edn` to tend toward autopretty: both `localize!` and `update!` cause the file to be written via `pprint`. I find this favorable, but perhaps it's not for everyone.

#### syntax:

The syntax of the `deps.config.edn` file is standard `edn`, and it looks similar to the `:deps` map of a  `deps.edn` file. However, it specifies both `:local/root` and `:git/url` for each specified library:

```
{'some/lib {:local/root "..." :git/url "..."}
 ...}
```

There is a more concise way to specify groups (loosely "repositories", which is probably a poor choice of terminology given the context) of related libraries as well:

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

This will read locally from: `/Users/example/dev/repo1/lib<1,2,...>`

And will push to: `https://github.com/repo1/lib<1,2,...>`.

Correct...it basically just allows for a naming convention to be exploited.

There are some other usage patterns as well, e.g. using `git@github.com` as the `:git/root` to use `ssh` to push to (potentially private) repositories, and specifying "repositories" within a single git repository by using `:git/url` instead of `:git/root`.

#### `git`

This library shells out to `git`, which means it will inherit configuration from the environment. The git interactions are altogether primitive.

Beyond allowing for the specification of commit messages, `nuid.deps` does very little in terms of specifying (or allowing the specification of) git commands—it will push to the currently checked out branch according to the environment configuration of `git commit` and `git push`. This includes `git hooks`, commit signing, etc..

#### `tools.deps` alias

This library also adds a forked and regularly rebased branch of `tools.deps` with `add-lib` included (at least until this feature makes it to master). It can be useful to add it as a standalone `alias` in `deps.edn` for dynamic `lib` and `dep` management, e.g.:

```
:aliases
{:repl
 {:extra-deps
  {nuid/deps
   {:git/url "https://github.com/nuid/deps.git",
    :sha "..."}}}}
```

When started with `clj -A:repl`, the REPL's classpath can be changed dynamically using `tools.deps/add-lib`. That change can be then persisted using `nuid.deps/add-dep!`. This functionality is experimental, and the API isn't the most empowering quite yet.

The dependencies of the project can also be `localize!`d and subsequently `update!`d without leaving the REPL. After an `update!`, it would require a REPL refresh to clone the new revisions into `gitlibs` and add them to the classpath, but the new revisions would be identical to the local coordinates already on the classpath after the original `localize!` and subsequent `(require '[...] :reload-all)`.

## Contributing

PRs would be most welcome! At the top of my list is `spec`ing the library, making the API more friendly in map, reduce, etc. for bulk operations, and revisiting the `git` interactions entirely.

Install [`git-hooks`](https://github.com/icefox/git-hooks) and fire away.

#### formatting:

```
$ clojure -A:cljfmt            # check
$ clojure -A:cljfmt:cljfmt/fix # fix
```

#### dependencies:

```
## check
$ npm outdated
$ clojure -A:depot

## update
$ npm upgrade -s
$ clojure -A:depot:depot/update
```

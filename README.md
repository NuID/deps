# nuid.deps

Dependency management facilities for [`tools.deps`](https://clojure.org/guides/deps_and_cli) workflows.

## Motivation

[`tools.deps`](https://clojure.org/guides/deps_and_cli) allows for the specification of dependencies at different types of coordinates (local, maven, and git). It allows developers to isolate functionality into small, modular libraries, but maintain the benefits commonly associated with the monorepo: code-sharing, circular-dependency avoidance, and frictionless local development of sibling modules. It really is [dependency heaven](https://www.youtube.com/watch?v=sStlTye-Kjk).

In rapidly developing codebases with many `:git/url` coordinates, it is often desirable to `localize!` the git dependencies of a project that are changing frequently, i.e. convert them to a  `:local/root` coordinate. Once relevant changes have been made, those local coordinates must be `update!`d to the appropriate git revision, which must be done in accordance with the dependency tree. This library automates those two tasks.

## Notes

`tools.deps` is alpha software, and `nuid.deps` is really just a few helper funtions on top of it. There are rough edges.

`nuid.deps` requires a map (e.g. `deps.config.edn`) that specifies how to find dependencies locally and where to push them when they are ready to be `update!`d to allow for variation between development environments (e.g. not everyone uses `~/dev/`). See `deps.config.example.edn` and below for more information.

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

... make changes to some.lib/in.config and it's dependencies that may also be specified in config

=> (deps/update! config 'some.lib/in.config)

... add commit messages to each updated dependency, including some.lib/in.config
... _committing and pushing_

=> ;; some.lib/in.config and all of it's dependencies that also appear in config now reference the most up-to-date git revisions in their own deps.edn

=> (def another-config (deps/read-config "/Users/example/dev/lib/another.deps.config.edn"))
=> ...
```

## `deps.config.edn`

The dependencies to be managed by this tool are specified in an `edn` configuration file. The default is `./deps.config.edn`.

The reason this file is necessary is because `deps.edn` can only reference one coordinate type at a time—it would have no way of disambiguating the intent e.g. if all of `:local/root`, `:git/url`, and `:git/sha` were specified for a given library.

The `deps.config.edn` (or whatever you name it) file allows `nuid.deps` to find the library locally in order to `localize!` its dependents, and also defines where `nuid.deps` will push the changes for `update!`.

I've found this file generally useful within the `tools.deps` ecosystem for reading and automated manipulation of `deps.edn` files.

#### syntax:

The syntax of the `deps.config.edn` file is standard `edn`, and it looks similar to the `:deps` map of a  `deps.edn` file. However, it specifies both `:local/root` and `:git/url` for each specified library:

```
{'some/lib {:local/root "..." :git/url "..."}
 ...}
```

There is a more concise way to specify groups (loosely "repositories", which is probably a poor choice of terminology given the context) of related libraries as well:

```
{'some/lib {:local/root "..." :git/url "..."},
 :deps/repositories
 [{:repository/root "/Users/example/dev"
 :git/root "https://github.com"
 :repository/libs
 [repo1/lib1
  repo1/lib2]}]}
```

This will read locally from `/Users/example/dev/repo1/lib<1,2,...>` and push to `https://github.com/repo1/lib<1,2,...>`.

There are some other usage patterns as well, e.g. using `git@github.com` as the `:git/root` to use `ssh` to push to (potentially private) repositories, and specifying "repositories" within a single git repository by using `:git/url` instead of `:git/root`.

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

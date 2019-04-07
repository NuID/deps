# nuid.deps

Dependency management facilities for [`tools.deps`](https://clojure.org/guides/deps_and_cli) workflows.

## Motivation

[`tools.deps`](https://clojure.org/guides/deps_and_cli) allows for the specification of dependencies at different types of coordinates (local, maven, and git). It allows developers to isolate functionality into small, modular libraries, but maintain the benefits commonly associated with the monorepo: code-sharing, circular-dependency avoidance, and frictionless local development of sibling modules. It really is [dependency heaven](https://www.youtube.com/watch?v=sStlTye-Kjk).

In rapidly developing codebases with many `:git/url` coordinates, it is often desirable to `localize!` the git dependencies of a project that are changing frequently, i.e. convert them to a  `:local/root` coordinate. Once relevant changes have been made, those local coordinates must be `update!`d to the appropriate git revision, which must be done in accordance with the dependency tree. This library automates those two tasks.

## Notes

`tools.deps` is alpha software, and `nuid.deps` is really just some helper funtions on top of it. There are rough edges.

`nuid.deps` is meant to be combined with a map (e.g. `deps.config.edn`) that specifies how to find dependencies locally and where to push them when they are ready to be `update!`d to allow for variation between development environments (e.g. not everyone uses `~/dev/`). See `deps.config.example.edn` and below for more information.

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
=> 
```

## Contributing

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

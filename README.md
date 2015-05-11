# txload

**txload** is a library that enables **transparent transactional loading of Clojure namespaces**.
This library modifies the Clojure runtime transparently such that ```require``` and ```use``` are thread-safe. 
The library is only needed when Clojure namespaces are loaded dynamically at runtime from different threads potentially at the same time.

This library is only needed until namespace loading is made transactional in Clojure.

## Install

Add the following to your dependency vector in your project.clj:

```clojure
[txload "0.1.1"]
```

Latest on [clojars.org](http://clojars.org):

![Version](https://clojars.org/txload/txload/latest-version.svg)

## Usage

txload has to be enabled before executing code that needs to load Clojure namespaces dynamically from different threads.

```clojure
(require '[txload.core :as tx])
(tx/enable)
```

There is also the possibility to disable txload again, e.g. when used in a REPL.

```clojure
(tx/disable)
```

For debugging purposes ```txload.core/*verbose*``` can be bound to ```true``` as in the following example:

```clojure
(binding [tx/*verbose* true]
  (require 'my.lib.core))
```

The [test](test/txload/core_test.clj) demonstrates the problematic scenario (strongly amplified).
If ```(enable)``` is removed, the test case will fail with an exception.

Reloading namespaces is not thouroughly tested, yet.
Reloading of one namespace ```(require 'my.lib :reload)``` should work.
Reloading a namespace and all its dependencies is implemented via locking to work correctly.
So concurrent calls to something like ```(require 'my.lib :reload-all)``` block and are executed one after the other.

## License

Copyright © 2014 Gunnar Völkel

Distributed under the Eclipse Public License.

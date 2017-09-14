# sidedocs-nrepl

Replace clojure API documentation displayed by CIDER.

sidedocs catches CIDER's request for documentation. It then inject
alternative documentation if available.

## Usage
### 1. Add sidedocs-nrepl to your CIDER repl
With Leinigen

```Clojure
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [cider/cider-nrepl "0.15.1"]
                 [odie/sidedocs-nrepl "0.1.0-SNAPSHOT" ]

  ...

  :repl-options {:nrepl-middleware
                 [...
                  sidedocs.nrepl/wrap-sidedocs]
```

With Boot

```Clojure
(swap! boot.repl/*default-dependencies*
       concat '[[sidedocs-nrepl "0.1.0-SNAPSHOT"]])

(swap! boot.repl/*default-middleware*
       concat '[sidedocs-nrepl.middleware/wrap-sidedocs])
```


### 2. Add alternative documentation!
With step 1 completed, sidedocs should be catching all `cider-doc` requests.
That's great, but sidedocs won't actually inject anything until it has some
alternative documentation to show.

Let's fix that!
```Shell
mkdir ~/.sidedocs
git clone https://github.com/Odie/sidedocs-clj-api-docs ~/.sidedocs/1-clj-api-docs
```

This gets you some alternate docs written by some random person on the internet!
If you dig into the directory a bit, you'll see the documentation is organized like this:

```
- ~/.sidedocs
|--- a doc repo
|    |-- a clojure namespace
|        |-- a var name.md
|
|--- another doc repo
```

Now when a `cider-doc` request comes through, sidedocs will look into each of
the repos (in string sort order) and return the first alternate documentation it
finds for a Var.


This gets us a few things:
1. Easy to add alternate documentation locally

   Just add a markdown file at the right location on disk and the docstring displayed by CIDER will be overridden.

2. Easy to share & update documentation

   Anybody can work on some documentation and put it in their own git repo.
   Sharing and updating can be done simply with `git clone` and `git pull`. This can also be automated.

## License

Copyright © 2017 Jonathan Shieh

Distributed under the Eclipse Public License either version 1.0

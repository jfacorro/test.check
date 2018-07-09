;   Copyright (c) Rich Hickey, Reid Draper, and contributors.
;   All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.test.check.generators
  (:refer-clojure :exclude [int vector list hash-map map keyword tuple
                            char boolean byte bytes sequence
                            shuffle not-empty symbol namespace
                            set sorted-set uuid double let])
  (:require [#?(:clj clojure.core :clje clojure.core :cljs cljs.core) :as core
             #?@(:cljs [:include-macros true])]
            [clojure.string :as string]
            [clojure.test.check.random :as random]
            [clojure.test.check.rose-tree :as rose]
            #?@(:cljs [[goog.string :as gstring]
                       [clojure.string]]))
  #?(:cljs (:require-macros [clojure.test.check.generators :refer [let]])))

;; Gen
;; (internal functions)
;; ---------------------------------------------------------------------------

(defrecord Generator [gen])

(defn generator?
  "Test if `x` is a generator. Generators should be treated as opaque values."
  [x]
  #?(:clje
     (or (instance? Generator x)
         (and (instance? clojerl.Var x)
              (-> x meta :gen?)))
     :default
     (instance? Generator x)))

(defn- make-gen
  [generator-fn]
  (Generator. generator-fn))

#?(:clje
   (defn call-gen
     "Internal function."
     {:no-doc true}
     [^Generator gen rnd size]
     ((.-gen gen) rnd size))
   :default
   (defn call-gen
     "Internal function."
     {:no-doc true}
     [{generator-fn :gen} rnd size]
     (generator-fn rnd size)))

(defn gen-pure
  "Internal function."
  {:no-doc true}
  [value]
  (make-gen
   (fn [rnd size]
     value)))

#?(:clje
   (defn gen-fmap
     "Internal function."
     {:no-doc true}
     [k ^Generator gen]
     (make-gen
      (fn [rnd size]
        (k ((.-gen gen) rnd size)))))
   :default
   (defn gen-fmap
     "Internal function."
     {:no-doc true}
     [k {h :gen}]
     (make-gen
      (fn [rnd size]
        (k (h rnd size))))))

#?(:clje
   (defn gen-bind
     "Internal function."
     {:no-doc true}
     [^Generator gen k]
     (make-gen
      (fn [rnd size]
        (core/let [[r1 r2] (random/split rnd)
                   inner ((.-gen gen) r1 size)
                   gen (k inner)]
          ((.-gen gen) r2 size)))))
   :default
   (defn gen-bind
     "Internal function."
     {:no-doc true}
     [{h :gen} k]
     (make-gen
      (fn [rnd size]
        (core/let [[r1 r2] (random/split rnd)
                   inner (h r1 size)
                   {result :gen} (k inner)]
          (result r2 size))))))

(defn lazy-random-states
  "Internal function.

  Given a random number generator, returns an infinite lazy sequence
  of random number generators."
  {:no-doc true}
  [rr]
  (lazy-seq
   (core/let [[r1 r2] (random/split rr)]
     (cons r1
           (lazy-random-states r2)))))

(defn- gen-tuple
  "Takes a collection of generators and returns a generator of vectors."
  [gens]
  (make-gen
   (fn [rnd size]
     (mapv #(call-gen % %2 size) gens (random/split-n rnd (count gens))))))

;; Exported generator functions
;; ---------------------------------------------------------------------------

#?(:clje
   (defn resolve-gen [gen-or-var]
     (if (instance? Generator gen-or-var)
       gen-or-var
       (gen-or-var))))

(defn fmap
  "Returns a generator like `gen` but with values transformed by `f`.
  E.g.:

      (gen/sample (gen/fmap str gen/nat))
      => (\"0\" \"1\" \"0\" \"1\" \"4\" \"3\" \"6\" \"6\" \"4\" \"2\")

  Also see gen/let for a macro with similar functionality."
  [f gen]
  (assert (generator? gen) "Second arg to fmap must be a generator")
  (gen-fmap #(rose/fmap f %) #?(:clje (resolve-gen gen) :default gen)))

(defn return
  "Creates a generator that always returns `value`,
  and never shrinks. You can think of this as
  the `constantly` of generators. E.g.:

      (gen/sample (gen/return 42))
      => (42 42 42 42 42 42 42 42 42 42)"
  [value]
  (gen-pure (rose/pure value)))

(defn- bind-helper
  [f]
  (fn [rose]
    (gen-fmap rose/join
              (make-gen
               (fn [rnd size]
                 (rose/fmap #(call-gen (f %) rnd size)
                            rose))))))

(defn bind
  "Creates a new generator that passes the result of `gen` into function
  `f`. `f` should return a new generator. This allows you to create new
  generators that depend on the value of other generators. For example,
  to create a generator of permutations which first generates a
  `num-elements` and then generates a shuffling of `(range num-elements)`:

      (gen/bind gen/nat
                ;; this function takes a value generated by
                ;; the generator above and returns a new generator
                ;; which shuffles the collection returned by `range`
                (fn [num-elements]
                  (gen/shuffle (range num-elements))))

  Also see gen/let for a macro with similar functionality."
  [generator f]
  (assert (generator? generator) "First arg to bind must be a generator")
  (gen-bind #?(:clje (resolve-gen generator) :default generator) (bind-helper f)))

;; Helpers
;; ---------------------------------------------------------------------------

(defn make-size-range-seq
  "Internal function."
  {:no-doc true}
  [max-size]
  (cycle (range 0 max-size)))

(defn sample-seq
  "Returns an infinite sequence of realized values from `generator`.

  Note that this function is a dev helper and is not meant to be used
  to build other generators."
  ([generator] (sample-seq generator 200))
  ([generator max-size]
   (core/let [r (random/make-random)
              size-seq (make-size-range-seq max-size)]
     (core/map #(rose/root (call-gen generator %1 %2))
               (lazy-random-states r)
               size-seq))))

(defn sample
  "Return a sequence of `num-samples` (default 10)
  realized values from `generator`.

  The sequence starts with small values from the generator, which
  probably do not reflect the variety of values that will be generated
  during a longer test run.

  Note that this function is a dev helper and is not meant to be used
  to build other generators."
  ([generator]
   (sample generator 10))
  ([generator num-samples]
   (assert (generator? generator) "First arg to sample must be a generator")
   (take num-samples (sample-seq #?(:clje (resolve-gen generator)
                                    :default generator)))))

(defn generate
  "Returns a single sample value from the generator.

  Note that this function is a dev helper and is not meant to be used
  to build other generators.

  Optional args:

  - size: the abstract size parameter, defaults to 30
  - seed: the seed for the random number generator, an integer"
  {:added "0.8.0"}
  ([generator]
   (generate generator 30))
  ([generator size]
   (core/let [rng (random/make-random)]
     (rose/root (call-gen #?(:clje (resolve-gen generator)
                             :default generator)
                          rng size))))
  ([generator size seed]
   (core/let [rng (random/make-random seed)]
     (rose/root (call-gen #?(:clje (resolve-gen generator)
                             :default generator)
                          rng size)))))

;; Internal Helpers
;; ---------------------------------------------------------------------------

(defn- halfs
  [n]
  (take-while #(not= 0 %) (iterate #(quot % 2) n)))

(defn- shrink-int
  [integer]
  (core/map #(- integer %) (halfs integer)))

(defn- int-rose-tree
  [value]
  (rose/make-rose value (core/map int-rose-tree (shrink-int value))))

;; calc-long is factored out to support testing the surprisingly tricky double math.  Note:
;; An extreme long value does not have a precision-preserving representation as a double.
;; Be careful about changing this code unless you understand what's happening in these
;; examples:
;;
;; (= (long (- Integer/MAX_VALUE (double (- Integer/MAX_VALUE 10)))) 10)
;; (= (long (- Long/MAX_VALUE (double (- Long/MAX_VALUE 10)))) 0)

(defn- calc-long
  [factor lower upper]
  ;; these pre- and post-conditions are disabled for deployment
  #_ {:pre [(float? factor) (>= factor 0.0) (< factor 1.0)
            (integer? lower) (integer? upper) (<= lower upper)]
      :post [(integer? %)]}
  ;; Use -' on width to maintain accuracy with overflow protection.
  #?(:clj
     (core/let [width (-' upper lower -1)]
       ;; Preserve long precision if the width is in the long range.  Otherwise, we must accept
       ;; less precision because doubles don't have enough bits to preserve long equivalence at
       ;; extreme values.
       (if (< width Long/MAX_VALUE)
         (+ lower (long (Math/floor (* factor width))))
         ;; Clamp down to upper because double math.
         (min upper (long (Math/floor (+ lower (* factor width)))))))
     ;; TODO: check if this is actually the way to do this since the BEAM has
     ;; arbitrary precision integers.
     :clje
     (erlang/round (+ lower (* factor (- upper lower))))
     :cljs
     (long (Math/floor (+ lower (- (* factor (+ 1.0 upper))
                                   (* factor lower)))))))

(defn- rand-range
  [rnd lower upper]
  {:pre [(<= lower upper)]}
  (calc-long (random/rand-double rnd) lower upper))

(defn sized
  "Creates a generator that depends on the size parameter.
  `sized-gen` is a function that takes an integer and returns
  a generator.

  Examples:

      ;; generates vectors of booleans where the length always exactly
      ;; matches the `size` parameter
      (gen/sample (gen/sized (fn [size] (gen/vector gen/boolean size))))
      => ([]
          [false]
          [true true]
          [false true false]
          [false true true true]
          [false false true true false]
          [false true false true true false]
          [true false true true true false false]
          [true true false false false true false false]
          [false false false true true false true false true])"
  [sized-gen]
  (make-gen
   (fn [rnd size]
     (core/let [sized-gen (sized-gen size)]
       (call-gen sized-gen rnd size)))))

;; Combinators and helpers
;; ---------------------------------------------------------------------------

(defn resize
  "Creates a new generator with `size` always bound to `n`.

      (gen/sample (gen/set (gen/resize 200 gen/double)))
      => (#{}
          #{-4.994772362980037E147}
          #{-4.234418056487335E-146}
          #{}
          #{}
          #{}
          #{NaN}
          #{8.142414100982609E-63}
          #{-3.58429955903876E-159 2.8563794617604296E-154
            4.1021360195776005E-100 1.9084564045332549E-38}
          #{-2.1582818131881376E83 -5.8460065493236117E48 9.729260993803226E166})"
  [n generator]
  (assert (generator? generator) "Second arg to resize must be a generator")
  (core/let [{:keys [gen]} #?(:clje (resolve-gen generator) :default generator)]
    (make-gen
     (fn [rnd _size]
       (gen rnd n)))))

(defn scale
  "Creates a new generator that modifies the size parameter by the
  given function. Intended to support generators with sizes that need
  to grow at different rates compared to the normal linear scaling.

      (gen/sample (gen/tuple (gen/scale #(/ % 10) gen/nat)
                             gen/nat
                             (gen/scale #(* % 10) gen/nat)))
      => ([0 0 0]  [0 1 2]  [0 2 13] [0 1 6]  [0 1 23]
          [0 2 42] [0 1 26] [0 1 12] [0 1 12] [0 0 3])"
  {:added "0.8.0"}
  ([f generator]
   (sized (fn [n] (resize (f n) generator)))))

(defn choose
  #?(:clj
     "Creates a generator that generates integers uniformly in the range
     `lower` to `upper`, inclusive.

         (gen/sample (gen/choose 200 800))
         => (331 241 593 339 643 718 688 473 247 694)"
     :clje
     "Create a generator that returns integer numbers in the range
     `lower` to `upper`, inclusive.

         (gen/sample (gen/choose 200 800))
         => (331 241 593 339 643 718 688 473 247 694)"
     :cljs
     "Creates a generator that generates integer numbers uniformly in
     the range `lower` to `upper`, inclusive.

         (gen/sample (gen/choose 200 800))
         => (331 241 593 339 643 718 688 473 247 694)")
  [lower upper]
  ;; cast to long to support doubles as arguments per TCHECK-73
  (core/let #?(:clj
               [lower (long lower)
                upper (long upper)]
               :clje ;; does nothing, arbitrary precision in BEAM
               []
               :cljs ;; does nothing, no long in cljs
               [])
    (make-gen
     (fn [rnd _size]
       (core/let [value (rand-range rnd lower upper)]
         (rose/filter
          #(and (>= % lower) (<= % upper))
          (int-rose-tree value)))))))

(defn one-of
  "Creates a generator that randomly chooses a value from the list of
  provided generators. Shrinks toward choosing an earlier generator,
  as well as shrinking the value generated by the chosen generator.

      (gen/sample (gen/one-of [gen/small-integer gen/boolean (gen/vector gen/small-integer)]))
      => (true [] -1 [0] [1 -4 -4 1] true 4 [] 6 true)"
  [generators]
  (assert (every? generator? generators)
          "Arg to one-of must be a collection of generators")
  (assert (seq generators)
          "one-of cannot be called with an empty collection")
  (core/let #?(:clje [generators (core/mapv resolve-gen generators)]
               :default [])
    (bind (choose 0 (dec (count generators)))
          #(nth generators %))))

(defn- pick
  "Returns an index into the `likelihoods` sequence."
  [likelihoods n]
  (->> likelihoods
       (reductions + 0)
       (rest)
       (take-while #(<= % n))
       (count)))

(defn frequency
  "Creates a generator that chooses a generator from `pairs` based on the
  provided likelihoods. The likelihood of a given generator being chosen is
  its likelihood divided by the sum of all likelihoods. Shrinks toward
  choosing an earlier generator, as well as shrinking the value generated
  by the chosen generator.

  Examples:

      (gen/sample (gen/frequency [[5 gen/small-integer] [3 (gen/vector gen/small-integer)] [2 gen/boolean]]))
      => (true [] -1 [0] [1 -4 -4 1] true 4 [] 6 true)"
  [pairs]
  (assert (every? (fn [[x g]] (and (number? x) (generator? g)))
                  pairs)
          "Arg to frequency must be a list of [num generator] pairs")
  (core/let [pairs (filter #?(:clje #(-> % first pos?) :default (comp pos? first)) pairs)
             #?@(:clje [pairs (core/mapv (fn [[x g]] [x (resolve-gen g)]) pairs)])
             total (apply + (core/map first pairs))]
    (assert (seq pairs)
            "frequency must be called with at least one non-zero weight")
    ;; low-level impl for shrinking control
    (make-gen
     (fn [rnd size]
       (call-gen
        (gen-bind (choose 0 (dec total))
                  (fn [x]
                    (core/let [idx (pick (core/map first pairs) (rose/root x))]
                      (gen-fmap (fn [rose]
                                  (rose/make-rose (rose/root rose)
                                                  (lazy-seq
                                                   (concat
                                                    ;; try to shrink to earlier generators first
                                                    (for [idx (range idx)]
                                                      (call-gen (second (nth pairs idx))
                                                                rnd
                                                                size))
                                                    (rose/children rose)))))
                                (second (nth pairs idx))))))
        rnd size)))))

(defn elements
  "Creates a generator that randomly chooses an element from `coll`.

      (gen/sample (gen/elements [:foo :bar :baz]))
      => (:foo :baz :baz :bar :foo :foo :bar :bar :foo :bar)"
  [coll]
  (assert (seq coll) "elements cannot be called with an empty collection")
  (core/let [v (vec coll)]
    (gen-fmap #(rose/fmap v %)
              (choose 0 (dec (count v))))))

(defn- such-that-helper
  [pred gen {:keys [ex-fn max-tries]} rng size]
  (loop [tries-left max-tries
         rng rng
         size size]
    (if (zero? tries-left)
      (throw (ex-fn {:pred pred, :gen, gen :max-tries max-tries}))
      (core/let [[r1 r2] (random/split rng)
                 value (call-gen gen r1 size)]
        (if (pred (rose/root value))
          (rose/filter pred value)
          (recur (dec tries-left) r2 (inc size)))))))

#?(:clje
   (defn ex-fn
     [{:keys [max-tries] :as arg}]
     (ex-info (str "Couldn't satisfy such-that predicate after "
                   max-tries " tries.")
              arg)))

(def ^:private
  default-such-that-opts
  {:ex-fn
   #?(:clje ex-fn
      :default
      (fn [{:keys [max-tries] :as arg}]
        (ex-info (str "Couldn't satisfy such-that predicate after "
                      max-tries " tries.")
                 arg)))
   :max-tries 10})

(defn such-that
  "Creates a generator that generates values from `gen` that satisfy predicate
  `pred`. Care is needed to ensure there is a high chance `gen` will satisfy
  `pred`. By default, `such-that` will try 10 times to generate a value that
  satisfies the predicate. If no value passes this predicate after this number
  of iterations, a runtime exception will be thrown. Note also that each
  time such-that retries, it will increase the size parameter.

  Examples:

      ;; generate non-empty vectors of integers
      ;; (note, gen/not-empty does exactly this)
      (gen/such-that not-empty (gen/vector gen/small-integer))

  You can customize `such-that` by passing an optional third argument, which can
  either be an integer representing the maximum number of times test.check
  will try to generate a value matching the predicate, or a map:

      :max-tries  positive integer, the maximum number of tries (default 10)
      :ex-fn      a function of one arg that will be called if test.check cannot
                  generate a matching value; it will be passed a map with `:gen`,
                  `:pred`, and `:max-tries` and should return an exception"
  ([pred gen]
   (such-that pred gen 10))
  ([pred gen max-tries-or-opts]
   (core/let [opts (cond (integer? max-tries-or-opts)
                         {:max-tries max-tries-or-opts}

                         (map? max-tries-or-opts)
                         max-tries-or-opts

                         :else
                         (throw (ex-info "Bad argument to such-that!" {:max-tries-or-opts
                                                                       max-tries-or-opts})))
              opts (merge default-such-that-opts opts)
              #?@(:clje [gen (resolve-gen gen)])]
     (assert (generator? gen) "Second arg to such-that must be a generator")
     (make-gen
      (fn [rand-seed size]
        (such-that-helper pred gen opts rand-seed size))))))

(defn not-empty
  "Modifies a generator so that it doesn't generate empty collections.

  Examples:

      ;; generate a vector of booleans, but never the empty vector
      (gen/sample (gen/not-empty (gen/vector gen/boolean)))
      => ([false]
          [false false]
          [false false]
          [false false false]
          [false false false false]
          [false true true]
          [true false false false]
          [true]
          [true true true false false true false]
          [false true true true false true true true false])"
  [gen]
  (assert (generator? gen) "Arg to not-empty must be a generator")
  (such-that core/not-empty #?(:clje (resolve-gen gen) :default gen)))

(defn no-shrink
  "Creates a new generator that is just like `gen`, except does not shrink
  at all. This can be useful when shrinking is taking a long time or is not
  applicable to the domain."
  [gen]
  (assert (generator? gen) "Arg to no-shrink must be a generator")
  (gen-fmap (fn [rose]
              (rose/make-rose (rose/root rose) []))
            #?(:clje (resolve-gen gen) :default gen)))

(defn shrink-2
  "Creates a new generator like `gen`, but will consider nodes for shrinking
  even if their parent passes the test (up to one additional level)."
  [gen]
  (assert (generator? gen) "Arg to shrink-2 must be a generator")
  (gen-fmap rose/collapse #?(:clje (resolve-gen gen) :default gen)))

#?(:clje
   (defmacro defgen [name & gendecl]
     (core/let [m       (if (string? (first gendecl))
                          {:doc (first gendecl)}
                          {})
                gendecl (if (string? (first gendecl))
                          (next gendecl)
                          gendecl)
                m       (assoc m :gen? true)]
       `(defn ~(with-meta name m) [] ~@gendecl))))

(#?(:clje defgen :default def) boolean
  "Generates one of `true` or `false`. Shrinks to `false`."
  (elements [false true]))

(defn tuple
  "Creates a generator that returns a vector, whose elements are chosen
  from the generators in the same position. The individual elements shrink
  according to their generator, but the vector will never shrink in count.

  Examples:

      (def t (gen/tuple gen/small-integer gen/boolean))
      (sample t)
      ;; => ([1 true] [2 true] [2 false] [1 false] [0 true] [-2 false] [-6 false]
      ;; =>  [3 true] [-4 false] [9 true]))"
  [& generators]
  (assert (every? generator? generators)
          "Args to tuple must be generators")
  (gen-fmap (fn [roses]
              (rose/zip core/vector roses))
            (gen-tuple #?(:clje (core/mapv resolve-gen generators)
                          :default generators))))

(#?(:clje defgen :default def) nat
  "Generates non-negative integers bounded by the generator's `size`
  parameter. Shrinks to zero."
  (sized (fn [size] (choose 0 size))))

(#?(:clje defgen :default def) ^{:added "0.10.0"} small-integer
  "Generates a positive or negative integer bounded by the generator's
  `size` parameter. Shrinks to zero."
  (sized (fn [size] (choose (- size) size))))

;; The following five are deprecated due to being confusingly named,
;; and in some cases not being very useful.
(def ^{:deprecated "0.10.0"} int
  "Deprecated - use gen/small-integer instead.

  Generates a positive or negative integer bounded by the generator's
  `size` parameter."
  small-integer)

(def ^{:deprecated "0.10.0"} pos-int
  "Deprecated - use gen/nat instead (see also gen/large-integer).

  (this generator, despite its name, can generate 0)

  Generates nonnegative integers bounded by the generator's `size` parameter."
  nat)

(#?(:clje defgen :default def) ^{:deprecated "0.10.0"} neg-int
  "Deprecated - use (gen/fmap - gen/nat) instead (see also gen/large-integer).

  (this generator, despite its name, can generate 0)

  Generates nonpositive integers bounded by the generator's `size` parameter."
  (fmap #(* -1 %) nat))

(#?(:clje defgen :default def) ^{:deprecated "0.10.0"} s-pos-int
  "Deprecated - use (gen/fmap inc gen/nat) instead (see also gen/large-integer).

  Generates positive integers bounded by the generator's `size` + 1"
  (fmap inc nat))

(#?(:clje defgen :default def) ^{:deprecated "0.10.0"} s-neg-int
  "Deprecated - use (gen/fmap (comp dec -) gen/nat) instead (see also gen/large-integer).

  Generates negative integers bounded by the generator's `size` + 1"
  (fmap dec neg-int))

(defn vector
  "Creates a generator of vectors whose elements are chosen from
  `generator`. The count of the vector will be bounded by the `size`
  generator parameter."
  ([generator]
   (assert (generator? generator) "Arg to vector must be a generator")
   (gen-bind
    (sized #(choose 0 %))
    (fn [num-elements-rose]
      (gen-fmap (fn [roses]
                  (rose/shrink-vector core/vector
                                      roses))
                (gen-tuple (repeat (rose/root num-elements-rose)
                                   #?(:clje (resolve-gen generator)
                                      :default generator)))))))
  ([generator num-elements]
   (assert (generator? generator) "First arg to vector must be a generator")
   (apply tuple (repeat num-elements #?(:clje (resolve-gen generator)
                                        :default generator))))
  ([generator min-elements max-elements]
   (assert (generator? generator) "First arg to vector must be a generator")
   (gen-bind
    (choose min-elements max-elements)
    (fn [num-elements-rose]
      (gen-fmap (fn [roses]
                  (rose/filter
                   (fn [v] (and (>= (count v) min-elements)
                                (<= (count v) max-elements)))
                   (rose/shrink-vector core/vector
                                       roses)))
                (gen-tuple (repeat (rose/root num-elements-rose)
                                   #?(:clje (resolve-gen generator)
                                      :default generator))))))))

(defn list
  "Like `vector`, but generates lists."
  [generator]
  (assert (generator? generator) "First arg to list must be a generator")
  (gen-bind (sized #(choose 0 %))
            (fn [num-elements-rose]
              (gen-fmap (fn [roses]
                          (rose/shrink-vector core/list
                                              roses))
                        (gen-tuple (repeat (rose/root num-elements-rose)
                                           #?(:clje (resolve-gen generator)
                                              :default generator)))))))

(defn- swap
  [coll [i1 i2]]
  (assoc coll i2 (coll i1) i1 (coll i2)))

(defn
  ^{:added "0.6.0"}
  shuffle
  "Creates a generator that generates random permutations of
  `coll`. Shrinks toward the original collection: `coll`. `coll` will
  be coerced to a vector."
  [coll]
  (core/let [coll (if (vector? coll) coll (vec coll))
             index-gen (choose 0 (dec (count coll)))]
    (fmap #(reduce swap coll %)
          ;; a vector of swap instructions, with count between
          ;; zero and 2 * count. This means that the average number
          ;; of instructions is count, which should provide sufficient
          ;; (though perhaps not 'perfect') shuffling. This still gives us
          ;; nice, relatively quick shrinks.
          (vector (tuple index-gen index-gen) 0 (* 2 (count coll))))))

;; NOTE cljs: Comment out for now - David

#?(:clj
   (def byte
     "Generates `java.lang.Byte`s, using the full byte-range."
     (fmap core/byte (choose Byte/MIN_VALUE Byte/MAX_VALUE))))

#?(:clj
   (def bytes
     "Generates byte-arrays."
     (fmap core/byte-array (vector byte))))

(defn hash-map
  "Like clojure.core/hash-map, except the values are generators.
   Returns a generator that makes maps with the supplied keys and
   values generated using the supplied generators.

       (gen/sample (gen/hash-map :a gen/boolean :b gen/nat))
       => ({:a false, :b 0}
           {:a true,  :b 1}
           {:a false, :b 2}
           {:a true,  :b 2}
           {:a false, :b 4}
           {:a false, :b 2}
           {:a true,  :b 3}
           {:a true,  :b 4}
           {:a false, :b 1}
           {:a false, :b 0})"
  [& kvs]
  (assert (even? (count kvs)))
  (core/let [ks (take-nth 2 kvs)
             vs (take-nth 2 (rest kvs))
             #?@(:clje [vs (core/mapv resolve-gen vs)])]
    (assert (every? generator? vs)
            "Value args to hash-map must be generators")
    (fmap #(zipmap ks %)
          (apply tuple vs))))

;; Collections of distinct elements
;; (has to be done in a low-level way (instead of with combinators)
;;  and is subject to the same kind of failure as such-that)
;; ---------------------------------------------------------------------------

(defn ^:private transient-set-contains?
  [s k]
  #? (:clj
      (.contains ^clojure.lang.ITransientSet s k)
      :cljs
      (some? (-lookup s k))))

(defn ^:private coll-distinct-by*
  "Returns a rose tree."
  [empty-coll key-fn shuffle-fn gen rng size num-elements min-elements max-tries ex-fn]
  {:pre [gen (:gen gen)]}
  (loop [rose-trees #?(:clje [] :default (transient []))
         s #?(:clje #{} :default (transient #{}))
         rng rng
         size size
         tries 0]
    (cond (and (= max-tries tries)
               (< (count rose-trees) min-elements))
          (throw (ex-fn {:gen gen
                         :max-tries max-tries
                         :num-elements num-elements}))

          (or (= max-tries tries)
              (= (count rose-trees) num-elements))
          (->> #?(:clje rose-trees
                  :default (persistent! rose-trees))
               ;; we shuffle the rose trees so that we aren't biased
               ;; toward generating "smaller" elements earlier in the
               ;; collection (only applies to ordered collections)
               ;;
               ;; shuffling the rose trees is more efficient than
               ;; (bind ... shuffle) because we only perform the
               ;; shuffling once and we have no need to shrink the
               ;; shufling.
               (shuffle-fn rng)
               (rose/shrink-vector #(into empty-coll %&)))

          :else
          (core/let [[rng1 rng2] (random/split rng)
                     rose (call-gen gen rng1 size)
                     root (rose/root rose)
                     k (key-fn root)]
            (if (#?(:clje contains? :default transient-set-contains?) s k)
              (recur rose-trees s rng2 (inc size) (inc tries))
              (recur (#?(:clje conj :default conj!) rose-trees rose)
                     (#?(:clje conj :default conj!) s k)
                     rng2
                     size
                     0))))))

(defn ^:private distinct-by?
  "Like clojure.core/distinct? but takes a collection instead of varargs,
  and returns true for empty collections."
  [f coll]
  (or (empty? coll)
      (apply distinct? (core/map f coll))))

(defn ^:private the-shuffle-fn
  "Returns a shuffled version of coll according to the rng.

  Note that this is not a generator, it is just a utility function."
  [rng coll]
  (core/let [empty-coll (empty coll)
             v (vec coll)
             card (count coll)
             dec-card (dec card)]
    (into empty-coll
          (first
           (reduce (fn [[v rng] idx]
                     (core/let [[rng1 rng2] (random/split rng)
                                swap-idx (rand-range rng1 idx dec-card)]
                       [(swap v [idx swap-idx]) rng2]))
                   [v rng]
                   (range card))))))

(defn ^:private coll-distinct-by
  [empty-coll key-fn allows-dupes? ordered? gen
   {:keys [num-elements min-elements max-elements max-tries ex-fn]
    :or {max-tries 10
         ex-fn #(ex-info "Couldn't generate enough distinct elements!" %)}}]
  (core/let [shuffle-fn (if ordered?
                          the-shuffle-fn
                          (fn [_rng coll] coll))
             hard-min-elements (or num-elements min-elements 1)]
    (if num-elements
      (core/let [size-pred #(= num-elements (count %))]
        (assert (and (nil? min-elements) (nil? max-elements)))
        (make-gen
         (fn [rng gen-size]
           (rose/filter
            (if allows-dupes?
              ;; is there a smarter way to do the shrinking than checking
              ;; the distinctness of the entire collection at each
              ;; step?
              (every-pred size-pred #(distinct-by? key-fn %))
              size-pred)
            (coll-distinct-by* empty-coll key-fn shuffle-fn gen rng gen-size
                               num-elements hard-min-elements max-tries ex-fn)))))
      (core/let [min-elements (or min-elements 0)
                 size-pred (if max-elements
                             #(<= min-elements (count %) max-elements)
                             #(<= min-elements (count %)))]
        (gen-bind
         (if max-elements
           (choose min-elements max-elements)
           (sized #(choose min-elements (+ min-elements %))))
         (fn [num-elements-rose]
           (core/let [num-elements (rose/root num-elements-rose)]
             (make-gen
              (fn [rng gen-size]
                (rose/filter
                 (if allows-dupes?
                   ;; same comment as above
                   (every-pred size-pred #(distinct-by? key-fn %))
                   size-pred)
                 (coll-distinct-by* empty-coll key-fn shuffle-fn gen rng gen-size
                                    num-elements hard-min-elements max-tries ex-fn)))))))))))

;; I tried to reduce the duplication in these docstrings with a macro,
;; but couldn't make it work in cljs.

(defn vector-distinct
  "Generates a vector of elements from the given generator, with the
  guarantee that the elements will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated vectors
    :min-elements  the min size of generated vectors
    :max-elements  the max size of generated vectors
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([gen] (vector-distinct gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to vector-distinct must be a generator!")
   (coll-distinct-by [] identity true true #?(:clje (resolve-gen gen) :default gen) opts)))

(defn list-distinct
  "Generates a list of elements from the given generator, with the
  guarantee that the elements will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated list
    :min-elements  the min size of generated list
    :max-elements  the max size of generated list
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([gen] (list-distinct gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to list-distinct must be a generator!")
   (coll-distinct-by () identity true true #?(:clje (resolve-gen gen) :default gen) opts)))

(defn vector-distinct-by
  "Generates a vector of elements from the given generator, with the
  guarantee that (map key-fn the-vector) will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated vectors
    :min-elements  the min size of generated vectors
    :max-elements  the max size of generated vectors
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([key-fn gen] (vector-distinct-by key-fn gen {}))
  ([key-fn gen opts]
   (assert (generator? gen) "Second arg to vector-distinct-by must be a generator!")
   (coll-distinct-by [] key-fn true true #?(:clje (resolve-gen gen) :default gen) opts)))

(defn list-distinct-by
  "Generates a list of elements from the given generator, with the
  guarantee that (map key-fn the-list) will be distinct.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated list
    :min-elements  the min size of generated list
    :max-elements  the max size of generated list
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([key-fn gen] (list-distinct-by key-fn gen {}))
  ([key-fn gen opts]
   (assert (generator? gen) "Second arg to list-distinct-by must be a generator!")
   (coll-distinct-by () key-fn true true #?(:clje (resolve-gen gen) :default gen) opts)))

(defn set
  "Generates a set of elements from the given generator.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated set
    :min-elements  the min size of generated set
    :max-elements  the max size of generated set
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([gen] (set gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to set must be a generator!")
   (coll-distinct-by #{} identity false false #?(:clje (resolve-gen gen) :default gen) opts)))

(defn sorted-set
  "Generates a sorted set of elements from the given generator.

  If the generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated set
    :min-elements  the min size of generated set
    :max-elements  the max size of generated set
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct values; it will be passed a map with
                   `:gen`, `:num-elements`, and `:max-tries` and should return an
                   exception"
  {:added "0.9.0"}
  ([gen] (sorted-set gen {}))
  ([gen opts]
   (assert (generator? gen) "First arg to sorted-set must be a generator!")
   (coll-distinct-by (core/sorted-set) identity false false #?(:clje (resolve-gen gen) :default gen) opts)))

(defn map
  "Creates a generator that generates maps, with keys chosen from
  `key-gen` and values chosen from `val-gen`.

  If the key generator cannot or is unlikely to produce enough distinct
  elements, this generator will fail in the same way as `such-that`.

  Available options:

    :num-elements  the fixed size of generated maps
    :min-elements  the min size of generated maps
    :max-elements  the max size of generated maps
    :max-tries     the number of times the generator will be tried before
                   failing when it does not produce distinct elements
                   (default 10)
    :ex-fn         a function of one arg that will be called if test.check cannot
                   generate enough distinct keys; it will be passed a map with
                   `:gen` (the key-gen), `:num-elements`, and `:max-tries` and
                   should return an exception"
  ([key-gen val-gen] (map key-gen val-gen {}))
  ([key-gen val-gen opts]
   (coll-distinct-by {} first false false (tuple key-gen val-gen) opts)))

;; large integers
;; ---------------------------------------------------------------------------

;; This approach has a few distribution edge cases, but is pretty good
;; for expected uses and is way better than nothing.

(#?(:clje defgen :default def) ^:private gen-raw-long
  "Generates a single uniformly random long, does not shrink."
  (make-gen (fn [rnd _size]
              (rose/pure (random/rand-long rnd)))))

(def ^:private MAX_INTEGER
  #?(:clj Long/MAX_VALUE
     :clje (dec (apply * (repeat 63 2)))
     :cljs (dec (apply * (repeat 53 2)))))
(def ^:private MIN_INTEGER
  #?(:clj Long/MIN_VALUE
     :clje (- MAX_INTEGER)
     :cljs (- MAX_INTEGER)))

(defn ^:private abs
  [x]
  #?(:clj (Math/abs (long x))
     :clje (erlang/abs x)
     :cljs (Math/abs x)))

(defn ^:private long->large-integer
  [bit-count x min max]
  (loop [res (-> x
                 (#?(:clj bit-shift-right
                     :cljs .shiftRight
                     :clje bit-shift-right)
                  (- 64 bit-count))
                 #?(:cljs .toNumber)
                 ;; so we don't get into an infinite loop bit-shifting
                 ;; -1
                 (cond-> (zero? min) (abs)))]
    (if (<= min res max)
      res
      (core/let [res' (- res)]
        (if (<= min res' max)
          res'
          (recur #?(:clj (bit-shift-right res 1)
                    :clje (bit-shift-right res 1)
                    ;; emulating bit-shift-right
                    :cljs (-> res
                              (cond-> (odd? res)
                                ((if (neg? res) inc dec)))
                              (/ 2)))))))))

(defn ^:private large-integer**
  "Like large-integer*, but assumes range includes zero."
  [min max]
  (sized (fn [size]
           (core/let [size (core/max size 1) ;; no need to worry about size=0
                      max-bit-count (core/min size #?(:clj 64 :cljs 54 :clje 64))]
             (gen-fmap (fn [rose]
                         (core/let [[bit-count x] (rose/root rose)]
                           (int-rose-tree (long->large-integer bit-count x min max))))
                       (tuple (choose 1 max-bit-count)
                              gen-raw-long))))))

(defn large-integer*
  "Like large-integer, but accepts options:

    :min  the minimum integer (inclusive)
    :max  the maximum integer (inclusive)

  Both :min and :max are optional.

      (gen/sample (gen/large-integer* {:min 9000 :max 10000}))
      => (9000 9001 9001 9002 9000 9003 9006 9030 9005 9044)"
  {:added "0.9.0"}
  [{:keys [min max]}]
  (core/let [min (or min MIN_INTEGER)
             max (or max MAX_INTEGER)]
    (assert (<= min max))
    (such-that #(<= min % max)
               (if (<= min 0 max)
                 (large-integer** min max)
                 (if (< max 0)
                   (fmap #(+ max %) (large-integer** (- min max) 0))
                   (fmap #(+ min %) (large-integer** 0 (- max min))))))))

(#?(:clje defgen :default def) ^{:added "0.9.0"} large-integer
  "Generates a platform-native integer from the full available range
  (in clj, 64-bit Longs, and in cljs, numbers between -(2^53 - 1) and
  (2^53 - 1)).

  Use large-integer* for more control."
  (large-integer* {}))

;; doubles
;; ---------------------------------------------------------------------------


;; This code is a lot more complex than any reasonable person would
;; expect, for two reasons:
;;
;; 1) I wanted the generator to start with simple values and grow with
;; the size parameter, as well as shrink back to simple values. I
;; decided to define "simple" as numbers with simpler (closer to 0)
;; exponents, with simpler fractional parts (fewer lower-level bits
;; set), and with positive being simpler than negative. I also wanted
;; to take optional min/max parameters, which complicates the hell out
;; of things.
;;
;; 2) It works in CLJS as well, which has fewer utility functions for
;; doubles, and I wanted it to work exactly the same way in CLJS just
;; to validate the whole cross-platform situation. It should generate
;; the exact same numbers on both platforms.
;;
;; Some of the lower level stuff could probably be less messy and
;; faster, especially for CLJS.

(def ^:private POS_INFINITY #?(:clj Double/POSITIVE_INFINITY, :cljs (.-POSITIVE_INFINITY js/Number)))
(def ^:private NEG_INFINITY #?(:clj Double/NEGATIVE_INFINITY, :cljs (.-NEGATIVE_INFINITY js/Number)))
(def ^:private MAX_POS_VALUE #?(:clj Double/MAX_VALUE, :clje 1.797e308, :cljs (.-MAX_VALUE js/Number)))
(def ^:private MIN_NEG_VALUE (- MAX_POS_VALUE))
(def ^:private NAN #?(:clj Double/NaN :cljs (.-NaN js/Number)))

(defn ^:private uniform-integer
  "Generates an integer uniformly in the range 0..(2^bit-count-1)."
  [bit-count]
  {:assert [(<= 0 bit-count 52)]}
  (if (<= bit-count 32)
    ;; the case here is just for cljs
    (choose 0 (case #?(:clje bit-count :default (long bit-count))
                32 0xffffffff
                31 0x7fffffff
                (-> 1 (bit-shift-left bit-count) dec)))
    (fmap (fn [[upper lower]]
            #? (:clj
                (-> upper (bit-shift-left 32) (+ lower))
                :clje
                (-> upper (bit-shift-left 32) (+ lower))
                :cljs
                (-> upper (* 0x100000000) (+ lower))))
          (tuple (uniform-integer (- bit-count 32))
                 (uniform-integer 32)))))

(defn ^:private scalb
  [x exp]
  #?(:clj (Math/scalb ^double x ^int exp)
     :clje (* x (math/pow 2 exp))
     :cljs (* x (.pow js/Math 2 exp))))

(defn ^:private fifty-two-bit-reverse
  "Bit-reverses an integer in the range [0, 2^52)."
  [n]
  #? (:clj
      (-> n (Long/reverse) (unsigned-bit-shift-right 12))
      :clje
      (loop [out 0
             n n
             out-shifter (math/pow 2 52)]
        (if (< n 1)
          (* out out-shifter)
          (recur (-> out (* 2) (+ (bit-and n 1)))
                 (core/int (/ n 2))
                 (/ out-shifter 2))))
      :cljs
      (loop [out 0
             n n
             out-shifter (Math/pow 2 52)]
        (if (< n 1)
          (* out out-shifter)
          (recur (-> out (* 2) (+ (bit-and n 1)))
                 (/ n 2)
                 (/ out-shifter 2))))))

(#?(:clje defgen :default def) ^:private backwards-shrinking-significand
  "Generates a 52-bit non-negative integer that shrinks toward having
  fewer lower-order bits (and shrinks to 0 if possible)."
  (fmap fifty-two-bit-reverse
        (sized (fn [size]
                 (gen-bind (choose 0 (min size 52))
                           (fn [rose]
                             (uniform-integer (rose/root rose))))))))

#?(:clje (def ^:private LOG2E 1.4426950408889634))

(defn ^:private get-exponent
  [x]
  #? (:clj
      (Math/getExponent ^Double x)
      :clje
      (if (zero? x)
        -1023
        (core/let [x (erlang/abs x)

                   res
                   (clj_utils/floor (* (math/log x) LOG2E))

                   t (scalb x (- res))]
          (cond (< t 1) (dec res)
                (<= 2 t) (inc res)
                :else res)))
      :cljs
      (if (zero? x)
        -1023
        (core/let [x (Math/abs x)

                   res
                   (Math/floor (* (Math/log x) (.-LOG2E js/Math)))

                   t (scalb x (- res))]
          (cond (< t 1) (dec res)
                (<= 2 t) (inc res)
                :else res)))))

(defn ^:private double-exp-and-sign
  "Generates [exp sign], where exp is in [-1023, 1023] and sign is 1
  or -1. Only generates values for exp and sign for which there are
  doubles within the given bounds."
  [lower-bound upper-bound]
  (letfn [(gen-exp [lb ub]
            (sized (fn [size]
                     (core/let [qs8 (bit-shift-left 1 (quot (min 200 size) 8))]
                       (cond (<= lb 0 ub)
                             (choose (max lb (- qs8)) (min ub qs8))

                             (< ub 0)
                             (choose (max lb (- ub qs8)) ub)

                             :else
                             (choose lb (min ub (+ lb qs8))))))))]
    (if (and (nil? lower-bound)
             (nil? upper-bound))
      (tuple (gen-exp -1023 1023)
             (elements [1.0 -1.0]))
      (core/let [lower-bound (or lower-bound MIN_NEG_VALUE)
                 upper-bound (or upper-bound MAX_POS_VALUE)
                 lbexp (max -1023 (get-exponent lower-bound))
                 ubexp (max -1023 (get-exponent upper-bound))]
        (cond (<= 0.0 lower-bound)
              (tuple (gen-exp lbexp ubexp)
                     (return 1.0))

              (<= upper-bound 0.0)
              (tuple (gen-exp ubexp lbexp)
                     (return -1.0))

              :else
              (fmap (fn [[exp sign :as pair]]
                      (if (or (and (neg? sign) (< lbexp exp))
                              (and (pos? sign) (< ubexp exp)))
                        [exp (- sign)]
                        pair))
                    (tuple
                     (gen-exp -1023 (max ubexp lbexp))
                     (elements [1.0 -1.0]))))))))

(defn ^:private block-bounds
  "Returns [low high], the smallest and largest numbers in the given
  range."
  [exp sign]
  (if (neg? sign)
    (core/let [[low high] (block-bounds exp (- sign))]
      [(- high) (- low)])
    (if (= -1023 exp)
      [0.0 (-> 1.0 (scalb 52) dec (scalb -1074))]
      [(scalb 1.0 exp)
       (-> 1.0 (scalb 52) dec (scalb (- exp 51)))])))

(defn ^:private double-finite
  [lower-bound upper-bound]
  {:pre [(or (nil? lower-bound)
             (nil? upper-bound)
             (<= lower-bound upper-bound))]}
  (core/let [pred (if lower-bound
                    (if upper-bound
                      #(<= lower-bound % upper-bound)
                      #(<= lower-bound %))
                    (if upper-bound
                      #(<= % upper-bound)))

             gen
             (fmap (fn [[[exp sign] significand]]
                     (core/let [;; 1.0 <= base < 2.0
                                base (inc (/ significand
                                             (#?(:clje math/pow :default Math/pow) 2 52)))
                                x (-> base (scalb exp) (* sign))]
                       (if (or (nil? pred) (pred x))
                         x
                         ;; Scale things a bit when we have a partial range
                         ;; to deal with. It won't be great for generating
                         ;; simple numbers, but oh well.
                         (core/let [[low high] (block-bounds exp sign)

                                    block-lb (cond-> low  lower-bound (max lower-bound))
                                    block-ub (cond-> high upper-bound (min upper-bound))
                                    x (+ block-lb (* (- block-ub block-lb) (- base 1)))]
                           (-> x (min block-ub) (max block-lb))))))
                   (tuple (double-exp-and-sign lower-bound upper-bound)
                          backwards-shrinking-significand))]
    ;; wrapping in the such-that is necessary for staying in bounds
    ;; during shrinking
    (cond->> gen pred (such-that pred))))

(defn double*
  #?(:clje
     "Generates a 64-bit floating point number. Options:

    :min       - minimum value (inclusive, default none)
    :max       - maximum value (inclusive, default none)

  Note that the min/max options must be finite numbers. Supplying a
  min precludes -Infinity, and supplying a max precludes +Infinity."
     :default
     "Generates a 64-bit floating point number. Options:

    :infinite? - whether +/- infinity can be generated (default true)
    :NaN?      - whether NaN can be generated (default true)
    :min       - minimum value (inclusive, default none)
    :max       - maximum value (inclusive, default none)

  Note that the min/max options must be finite numbers. Supplying a
  min precludes -Infinity, and supplying a max precludes +Infinity.")
  {:added "0.9.0"}
  [#?(:clje
      {:keys [min max]}
      :default
      {:keys [infinite? NaN? min max]
       :or {infinite? true, NaN? true}})]
  (core/let [frequency-arg (cond-> [[95 (double-finite min max)]]

                             (if (nil? min)
                               (or (nil? max) (<= 0.0 max))
                               (if (nil? max)
                                 (<= min 0.0)
                                 (<= min 0.0 max)))
                             (conj
                              ;; Add zeros here as a special case, since
                              ;; the `finite` code considers zeros rather
                              ;; complex (as they have a -1023 exponent)
                              ;;
                              ;; I think most uses can't distinguish 0.0
                              ;; from -0.0, but seems worth throwing both
                              ;; in just in case.
                              [1 (return 0.0)]
                              [1 (return -0.0)])

                             #?@(:clje []
                                 :default
                                 [(and infinite? (nil? max))
                                  (conj [1 (return POS_INFINITY)])

                                  (and infinite? (nil? min))
                                  (conj [1 (return NEG_INFINITY)])

                                  NaN? (conj [1 (return NAN)])]))]
    (if (= 1 (count frequency-arg))
      (-> frequency-arg first second)
      (frequency frequency-arg))))

(#?(:clje defgen :default def) ^{:added "0.9.0"} double
  "Generates 64-bit floating point numbers from the entire range,
  including +/- infinity and NaN. Use double* for more control."
  (double* {}))

;; bigints
;; ---------------------------------------------------------------------------

#?(:clj
   (defn ^:private two-pow
     [exp]
     (bigint (.shiftLeft (biginteger 1) exp))))
#?(:clj (def ^:private dec-2-32 0xFFFFFFFF))
#?(:clj (def ^:private just-2-32 0x100000000))

;; could potentially make this public
#?(:clj
   (defn ^:private bounded-bigint
     "Generates bigints having the given max-bit-length, i.e. exclusively
  between -(2^(max-bit-length)) and 2^(max-bit-length)."
     [max-bit-length]
     (fmap
      (fn [xs]
        ;; is there a better way to avoid the auto-boxing warnings
        ;; than these Long constructor calls? I'd rather not start out
        ;; with bigints since that seems unnecessarily wasteful
        (loop [multiple  (Long. 1)
               bits-left max-bit-length
               xs        xs
               res       (Long. 0)]
          (cond (<= 32 bits-left)
                (recur (*' just-2-32 multiple)
                       (- bits-left 32)
                       (rest xs)
                       (+' res (*' multiple (first xs))))

                (pos? bits-left)
                (core/let [x (-> xs
                                 first
                                 (bit-shift-right (- 32 bits-left)))]
                  (+' res (*' multiple x)))

                :else
                res)))
      (vector (choose 0 dec-2-32)
              (Math/ceil (/ (core/double max-bit-length) 32))))))

#?(:clj
   (def ^:private size-bounded-bignat
     (core/let [poor-shrinking-gen
                (sized
                 (fn [size]
                   (bind (choose 0 (max 0 (* size 6)))
                         (fn [bit-count]
                           (if (zero? bit-count)
                             (return 0)
                             (fmap #(+' % (two-pow (dec bit-count)))
                                   (bounded-bigint (dec bit-count))))))))]
       (gen-fmap (fn [rose] (int-rose-tree (rose/root rose)))
                 poor-shrinking-gen))))

;; I avoided supporting min/max parameters because they could
;; contradict the size-boundedness
;;
;; I suppose a size-bounded-bigint* could be added with min/max that
;; throws if the resulting intersection is empty, but maybe that's
;; weird.
#?(:clj
   (def ^{:added "0.10.0"} size-bounded-bigint
     ;; 2^(6*size) was chosen so that with size=200 the generator could
     ;; generate values larger than Double/MAX_VALUE
     "Generates an integer (long or bigint) bounded exclusively by ±2^(6*size)."
     (fmap (fn [[n negate? force-bigint?]]
             (cond-> n
               negate? -'
               ;; adds some exciting variety
               force-bigint? bigint))
           (tuple size-bounded-bignat
                  boolean
                  boolean))))

;; Characters & Strings
;; ---------------------------------------------------------------------------

(#?(:clje defgen :default def) char
  "Generates character from 0-255."
  (fmap core/char (choose 0 255)))

(#?(:clje defgen :default def) char-ascii
  "Generates only ascii characters."
  (fmap core/char (choose 32 126)))

(#?(:clje defgen :default def) char-alphanumeric
  "Generates alphanumeric characters."
  (fmap core/char
           (one-of [(choose 48 57)
                    (choose 65 90)
                    (choose 97 122)])))

(def ^{:deprecated "0.6.0"}
  char-alpha-numeric
  "Deprecated - use char-alphanumeric instead.

  Generates alphanumeric characters."
  char-alphanumeric)

(#?(:clje defgen :default def) char-alpha
  "Generates alpha characters."
  (fmap core/char
           (one-of [(choose 65 90)
                    (choose 97 122)])))

(#?(:clje defgen :default def) ^:private char-symbol-special
  "Generates non-alphanumeric characters that can be in a symbol."
  (elements [\* \+ \! \- \_ \? \.]))

(#?(:clje defgen :default def) ^:private char-symbol-noninitial
  "Generates characters that can be the char following first of a keyword or symbol."
  (frequency [[14 char-alphanumeric]
              [7 char-symbol-special]
              [1 (return \:)]]))

(#?(:clje defgen :default def) ^:private char-symbol-initial
  "Generates characters that can be the first char of a keyword or symbol."
  (frequency [[2 char-alpha]
              [1 char-symbol-special]]))

(#?(:clje defgen :default def) string
  "Generates strings. May generate unprintable characters."
  (fmap clojure.string/join (vector char)))

(#?(:clje defgen :default def) string-ascii
  "Generates ascii strings."
  (fmap clojure.string/join (vector char-ascii)))

(#?(:clje defgen :default def) string-alphanumeric
  "Generates alphanumeric strings."
  (fmap clojure.string/join (vector char-alphanumeric)))

(def ^{:deprecated "0.6.0"}
  string-alpha-numeric
  "Deprecated - use string-alphanumeric instead.

  Generates alphanumeric strings."
  string-alphanumeric)

(defn- digit?
  [d]
  #?(:clj  (Character/isDigit ^Character d)
     :clje (<= "0" d "9")
     :cljs (gstring/isNumeric d)))

(defn- +-or---digit?
  "Returns true if c is \\+ or \\- and d is non-nil and a digit.

  Symbols that start with +3 or -2 are not readable because they look
  like numbers."
  [c d]
  (core/boolean (and d
                     (or (#?(:clj = :clje identical? :cljs identical?) \+ c)
                         (#?(:clj = :clje identical? :cljs identical?) \- c))
                     (digit? d))))

(#?(:clje defgen :default def) ^:private symbol-name-or-namespace
  "Generates a namespace string for a symbol/keyword."
  (->> (tuple char-symbol-initial (vector char-symbol-noninitial))
     (such-that (fn [[c [d]]] (not (+-or---digit? c d))))
     (fmap (fn [[c cs]]
             (core/let [s (clojure.string/join (cons c cs))]
               (-> s
                   (string/replace #":{2,}" ":")
                   (string/replace #":$" "")))))))

(defn ^:private resize-symbolish-generator
  "Scales the sizing down on a keyword or symbol generator so as to
  make it reasonable."
  [g]
  ;; function chosen by ad-hoc experimentation
  #?(:clje (scale #(math/pow % 0.60) g)
     :default (scale #(long (Math/pow % 0.60)) g)))

(#?(:clje defgen :default def) keyword
  "Generates keywords without namespaces."
  (->> symbol-name-or-namespace
       (fmap core/keyword)
       (resize-symbolish-generator)))

(#?(:clje defgen :default def)
  ^{:added "0.5.9"}
  keyword-ns
  "Generates keywords with namespaces."
  (->> (tuple symbol-name-or-namespace symbol-name-or-namespace)
       (fmap (fn [[ns name]] (core/keyword ns name)))
       (resize-symbolish-generator)))

(#?(:clje defgen :default def) symbol
  "Generates symbols without namespaces."
  (frequency [[100
               (->> symbol-name-or-namespace
                    (fmap core/symbol)
                    (resize-symbolish-generator))]
              [1 (return '/)]]))

(#?(:clje defgen :default def)
  ^{:added "0.5.9"}
  symbol-ns
  "Generates symbols with namespaces."
  (->> (tuple symbol-name-or-namespace symbol-name-or-namespace)
       (fmap (fn [[ns name]] (core/symbol ns name)))
       (resize-symbolish-generator)))

(#?(:clje defgen :default def) ratio
  "Generates a small ratio (or integer) using gen/small-integer. Shrinks
  toward simpler ratios, which may be larger or smaller."
  (fmap
   (fn [[a b]] (/ a b))
   (tuple small-integer (fmap inc nat))))

#?(:clj
   (def ^{:added "0.10.0"} big-ratio
     "Generates a ratio (or integer) using gen/size-bounded-bigint. Shrinks
  toward simpler ratios, which may be larger or smaller."
     (fmap
      (fn [[a b]] (/ a b))
      (tuple size-bounded-bignat
             (such-that (complement zero?) size-bounded-bignat)))))

#?(:clje
   (defgen ^{:added "0.9.0"} uuid
     "Generates a random type-4 UUID. Does not shrink."
     (no-shrink
      (make-gen
       (fn [rng _size]
         (core/let [[r1 r2] (random/split rng)
               [r3 _] (random/split rng)
               x1 (random/rand-long r1)
               x2 (random/rand-long r2)
               x3 (random/rand-long r3)]
           (rose/make-rose (erlang.util.UUID/random x1 x2 x3) []))))))
   :default
   (def ^{:added "0.9.0"} uuid
     "Generates a random type-4 UUID. Does not shrink."
     (no-shrink
      #?(:clj
         ;; this could be done with combinators, but doing it low-level
         ;; seems to be 10x faster
         (make-gen
          (fn [rng _size]
            (core/let [[r1 r2] (random/split rng)
                       x1 (-> (random/rand-long r1)
                              (bit-and -45057)
                              (bit-or 0x4000))
                       x2 (-> (random/rand-long r2)
                              (bit-or -9223372036854775808)
                              (bit-and -4611686018427387905))]
              (rose/make-rose
               (java.util.UUID. x1 x2)
               []))))
         :cljs
         ;; this could definitely be optimized so that it doesn't require
         ;; generating 31 numbers
         (fmap (fn [nibbles]
                 (letfn [(hex [idx] (.toString (nibbles idx) 16))]
                   (core/let [rhex (-> (nibbles 15) (bit-and 3) (+ 8) (.toString 16))]
                     (core/uuid (str (hex 0)  (hex 1)  (hex 2)  (hex 3)
                                     (hex 4)  (hex 5)  (hex 6)  (hex 7)  "-"
                                     (hex 8)  (hex 9)  (hex 10) (hex 11) "-"
                                     "4"      (hex 12) (hex 13) (hex 14) "-"
                                     rhex     (hex 16) (hex 17) (hex 18) "-"
                                     (hex 19) (hex 20) (hex 21) (hex 22)
                                     (hex 23) (hex 24) (hex 25) (hex 26)
                                     (hex 27) (hex 28) (hex 29) (hex 30))))))
               (vector (choose 0 15) 31))))))

(#?(:clje defgen :default def) ^:private base-simple-type
  [double-gen char-gen string-gen]
  (one-of [int #?(:clj size-bounded-bigint :cljs large-integer) double-gen char-gen
           string-gen ratio boolean keyword keyword-ns symbol symbol-ns uuid]))

(#?(:clje defgen :default def) simple-type
  "Generates a variety of scalar types."
  (base-simple-type double char string))

(#?(:clje defgen :default def) simple-type-printable
  "Generates a variety of scalar types, with printable strings."
  (base-simple-type double char-ascii string-ascii))

(#?(:clje defgen :default def) ^{:added "0.10.0"} simple-type-equatable
  "Like gen/simple-type, but only generates objects that can be
  equal to other objects (e.g., not a NaN)."
  (base-simple-type (double* {:NaN? false}) char string))

(#?(:clje defgen :default def) ^{:added "0.10.0"} simple-type-printable-equatable
  "Like gen/simple-type-printable, but only generates objects that
  can be equal to other objects (e.g., not a NaN)."
  (base-simple-type (double* {:NaN? false}) char-ascii string-ascii))

#?(:cljs
;; https://clojure.atlassian.net/browse/CLJS-1594
   (defn ^:private hashable?
     [x]
     (if (number? x)
       (not (or (js/isNaN x)
                (= NEG_INFINITY x)
                (= POS_INFINITY x)))
       true)))

(defn container-type
  [inner-type]
  (one-of [(vector inner-type)
           (list inner-type)
           (set #?(:clj inner-type
                   :clje inner-type
                   :cljs (such-that hashable? inner-type)))
           ;; scaling this by half since it naturally generates twice
           ;; as many elements
           (scale #(quot % 2)
                  (map #?(:clj inner-type
                          :clje inner-type
                          :cljs (such-that hashable? inner-type))
                       inner-type))]))

;; A few helpers for recursive-gen

(defn ^:private size->max-leaf-count
  [size]
  ;; chosen so that recursive-gen (with the assumptions mentioned in
  ;; the comment below) will generate structures with leaf-node-counts
  ;; not greater than the `size` ~99% of the time.
  #?(:clje (math/pow size 1.1)
     :default (long (Math/pow size 1.1))))

#?(:clje (def log2 (math/log 2)))

(core/let #?(:clje []
             :default [log2 (Math/log 2)])
  (defn ^:private random-pseudofactoring
    "Returns (not generates) a random collection of integers `xs`
  greater than 1 such that (<= (apply * xs) n)."
    [n rng]
    (if (<= n 2)
      [n]
      (core/let [log #?(:clje (math/log n) :default (Math/log n))
                 [r1 r2] (random/split rng)
                 n1 (-> (random/rand-double r1)
                        (* (- log log2))
                        (+ log2)
                        #?(:clje (math/exp) :default (Math/exp))
                        #?(:clj (long) :cljs (long)))
                 n2 (quot n n1)]
        (if (and (< 1 n1) (< 1 n2))
          (cons n1 (random-pseudofactoring n2 r2))
          [n])))))

(defn ^:private randomized
  "Like sized, but passes an rng instead of a size."
  [func]
  (make-gen (fn [rng size]
              (core/let [[r1 r2] (random/split rng)]
                (call-gen
                 (func r1)
                 r2
                 size)))))

(defn
  ^{:added "0.5.9"}
  recursive-gen
  "This is a helper for writing recursive (tree-shaped) generators. The first
  argument should be a function that takes a generator as an argument, and
  produces another generator that 'contains' that generator. The vector function
  in this namespace is a simple example. The second argument is a scalar
  generator, like boolean. For example, to produce a tree of booleans:

    (gen/recursive-gen gen/vector gen/boolean)

  Vectors or maps either recurring or containing booleans or integers:

    (gen/recursive-gen (fn [inner] (gen/one-of [(gen/vector inner)
                                                (gen/map inner inner)]))
                       (gen/one-of [gen/boolean gen/small-integer]))

  Note that raw scalar values will be generated as well. To prevent this, you
  can wrap the returned generator with the function passed as the first arg,
  e.g.:

    (gen/vector (gen/recursive-gen gen/vector gen/boolean))"
  [container-gen-fn scalar-gen]
  (assert (generator? scalar-gen)
          "Second arg to recursive-gen must be a generator")
  ;; The trickiest part about this is sizing. The strategy here is to
  ;; assume that the container generators will (like the normal
  ;; collection generators in this namespace) have a size bounded by
  ;; the `size` parameter, and with that assumption we can give an
  ;; upper bound to the total number of leaf nodes in the generated
  ;; structure.
  ;;
  ;; So we first pick an upper bound, and pick it to be somewhat
  ;; larger than the real `size` since on average they will be rather
  ;; smaller. Then we factor that upper bound into integers to give us
  ;; the size to use at each depth, assuming that the total size
  ;; should sort of be the product of the factored sizes.
  ;;
  ;; This is all a bit weird and hard to explain precisely but I think
  ;; it works reasonably and definitely better than the old code.
  (core/let [#?@(:clje [scalar-gen (resolve-gen scalar-gen)])]
    (sized (fn [size]
             (bind (choose 0 (size->max-leaf-count size))
                   (fn [max-leaf-count]
                     (randomized
                      (fn [rng]
                        (core/let [sizes (random-pseudofactoring max-leaf-count rng)
                                   sized-scalar-gen (resize size scalar-gen)]
                          (reduce (fn [g size]
                                    (bind (choose 0 10)
                                          (fn [x]
                                            (if (zero? x)
                                              sized-scalar-gen
                                              (resize size
                                                      (container-gen-fn g))))))
                                  sized-scalar-gen
                                  sizes))))))))))

(#?(:clje defgen :default def) any
  "A recursive generator that will generate many different, often nested, values"
  (recursive-gen container-type simple-type))

(#?(:clje defgen :default def) any-printable
  "Like any, but avoids characters that the shell will interpret as actions,
  like 7 and 14 (bell and alternate character set command)"
  (recursive-gen container-type simple-type-printable))

(def ^{:added "0.10.0"} any-equatable
  "Like any, but only generates objects that can be equal to other objects (e.g., do
  not contain a NaN)"
  (recursive-gen container-type simple-type-equatable))

(def ^{:added "0.10.0"} any-printable-equatable
  "Like any, but avoids characters that the shell will interpret as actions,
  like 7 and 14 (bell and alternate character set command), and only generates
  objects that can be equal to other objects (e.g., do not contain a NaN)"
  (recursive-gen container-type simple-type-printable-equatable))



;; Macros
;; ---------------------------------------------------------------------------

(defmacro let
  "Macro for building generators using values from other generators.
  Uses a binding vector with the same syntax as clojure.core/let,
  where the right-hand side of the binding pairs are generators, and
  the left-hand side are names (or destructuring forms) for generated
  values.

  Subsequent generator expressions can refer to the previously bound
  values, in the same way as clojure.core/let.

  The body of the let can be either a value or a generator, and does
  the expected thing in either case. In this way let provides the
  functionality of both `bind` and `fmap`.

  Examples:

    (gen/let [strs (gen/not-empty (gen/list gen/string))
              s (gen/elements strs)]
      {:some-strings strs
       :one-of-those-strings s})

    ;; generates collections of \"users\" that have integer IDs
    ;; from 0...N-1, but are in a random order
    (gen/let [users (gen/list (gen/hash-map :name gen/string-ascii
                                            :age gen/nat))]
      (->> users
           (map #(assoc %2 :id %1) (range))
           (gen/shuffle)))"
  {:added "0.9.0"}
  [bindings & body]
  (assert (vector? bindings)
          "First arg to gen/let must be a vector of bindings.")
  (assert (even? (count bindings))
          "gen/let requires an even number of forms in binding vector")
  (if (empty? bindings)
    `(core/let [val# (do ~@body)]
       (if (clojure.test.check.generators/generator? val#)
         #?(:clje (resolve-gen val#) :default val#)
         (return val#)))
    (core/let [[binding gen & more] bindings]
      `(clojure.test.check.generators/bind ~gen (fn [~binding] (let [~@more] ~@body))))))

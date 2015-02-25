(ns metabase.driver.generic-sql.query-processor
  "The Query Processor is responsible for translating the Metabase Query Language into korma SQL forms."
  (:require [clojure.core.match :refer [match]]
            [korma.core :refer :all]
            [metabase.db :refer :all]
            (metabase.driver.generic-sql.query-processor [annotate :as annotate]
                                                         [test-queries :refer :all])
            (metabase.models [field :refer [Field]]
                             [table :refer [Table]])))

(declare apply-form
         log-query
         field-id->kw
         table-id->korma-entity)

;; ## Public Functions

(def ^:dynamic *enable-debug-logging*
  "Log the query dictionary, korma form, and SQL output when running the Query Processor?"
  true)

(defn process
  "Convert QUERY into a korma `select` form."
  [{{:keys [source_table] :as query} :query}]
  (let [forms (->> (map apply-form query) ; call `apply-form` for each clause and strip out nil results
                   (filter identity)
                   (mapcat (fn [form] (if (vector? form) form
                                         [form])))
                   doall)]
    (when *enable-debug-logging*
      (log-query query forms))
    `(let [entity# (table-id->korma-entity ~source_table)]
       (select entity# ~@forms))))

(defn process-and-run
  "Convert QUERY into a korma `select` form, execute it, and annotate the results."
  [query]
  {:pre [(integer? (:database query)) ; double check that the query being passed is valid
         (map? (:query query))
         (= (name (:type query)) "query")]}
  (binding [*log-db-calls* false]
    (->> (process query)
         eval
         (annotate/annotate query))))


;; ## Query Clause Processors

(defmulti apply-form
  "Given a Query clause like

    {:aggregation [\"count\"]}

  call the matching implementation which should either return `nil` or translate it into a korma clause like

    (aggregate (count :*) :count)"
  (fn [[clause-name clause-value]] clause-name))

;; ### `:aggregation`
;; ex.
;;
;;    ["distinct" 1412]
(defmethod apply-form :aggregation [[_ value]]
  (match value
    ["rows"]  nil                                  ; don't need to do anything special for `rows` - `select` selects all rows by default
    ["count"] `(aggregate (~'count :*) :count)     ; TODO - implement other types of aggregation
    [_ _]     (let [[ag-type field-id] value       ; valid values to `korma.core/aggregate`: count, sum, avg, min, max, first, last
                    field (field-id->kw field-id)]
                (match ag-type
                  "distinct" `(aggregate (~'count (raw ~(format "DISTINCT(\"%s\")" (name field)))) :count)
                  "sum"      `(aggregate (~'sum ~field) :sum)
                  "avg"      `(aggregate (~'avg ~field) :avg)
                  "stddev"   `(fields [(sqlfn :stddev ~field) :stddev])))))

;; `:breakout`
;; ex.
;;
;; [1412 1413]
(defmethod apply-form :breakout [[_ field-ids]] ; TODO - not yet implemented
  (when-not (= field-ids [nil])                      ; `:breakout [nil]` is considered a valid 'empty' form
    (let [field-names (map field-id->kw field-ids)]
      `[(group ~@field-names)
        (fields ~@field-names)])))

;; ### `:fields`
;; ex.
;;
;;    [1412 1413]
(defmethod apply-form :fields [[_ field-ids]]
  (let [field-names (->> (sel :many [Field :name] :id [in (set field-ids)])
                         (map :name))]
    `(fields ~@field-names)))

;; ### `:filter`
;; ex.
;;
;;    ["AND"
;;      [">" 1413 1]
;;      [">=" 1412 4]]
(defmethod apply-form :filter [[_ filter-clause]]
  (match filter-clause
    [nil nil]       nil ; empty clause
    ["AND" & forms] `(where ~(->> (rest filter-clause)                                           ; so far only `AND` filtering is available in the UI
                                  (map (fn [[filter-type field-id value]]                         ; just convert filter-types like `"<="` directly to symbols
                                         {(field-id->kw field-id) [(symbol filter-type) value]})
                                  (apply merge {}))))))

;; ### `:limit`
;; ex.
;;
;;    10
(defmethod apply-form :limit [[_ value]]
  (when value
    `(limit ~value)))

;; ### `:order_by`
;; ex.
;;
;;    [[1416 "ascending"]
;;     [1412 "descending"]]
(defmethod apply-form :order_by [[_ field-ids]]
  (when-not (empty? field-ids)
    (let [fields (->> field-ids
                      (mapcat (fn [[field-id asc-desc]]
                                [(field-id->kw field-id) (case asc-desc
                                                           "ascending" :ASC
                                                           "descending" :DESC)])))]
      `(order ~@fields))))

;; ### `:source_table`
(defmethod apply-form :source_table [_] ; nothing to do here since getting the `Table` is handled by `process`
  nil)


;; ## Utility Functions (Internal)

(defn table-id->korma-entity
  "Lookup `Table` with TABLE-ID and return a korma entity that can be used in a korma form."
  [table-id]
  {:pre [(integer? table-id)]
   :post [(map? %)]}
  (let [{:keys [korma-entity]} (sel :one Table :id table-id)]
    @korma-entity))

(defn- field-id->kw
  "Lookup `Field` with FIELD-ID and return its name as a keyword (suitable for use in a korma clause)."
  [field-id]
  {:pre [(integer? field-id)]
   :post [(keyword? %)]}
  (-> (sel :one [Field :name] :id field-id)
      :name
      keyword))


;; ## Debugging Functions (Internal)

(defn- log-query
  "Log QUERY Dictionary and the korma form and SQL that the Query Processor translates it to."
  [{:keys [source_table] :as query} forms]
  (println "\nQUERY ->")
  (clojure.pprint/pprint query)
  (println "\nKORMA FORM ->")
  (clojure.pprint/pprint `(select (table-id->korma-entity ~source_table) ~@forms))
  (eval `(let [entity# (table-id->korma-entity ~source_table)]
           (println "\nSQL ->")
           (println (sql-only (select entity# ~@forms)) "\n"))))

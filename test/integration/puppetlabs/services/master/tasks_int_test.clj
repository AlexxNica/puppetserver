(ns puppetlabs.services.master.tasks-int-test
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap]
            [puppetlabs.puppetserver.testutils :as testutils]
            [cheshire.core :as json]
            [me.raynes.fs :as fs]))

(def test-resources-dir
  "./dev-resources/puppetlabs/services/master/tasks_int_test")

(defn purge-env-dir
  []
  (-> testutils/conf-dir
      (fs/file "environments")
      fs/delete-dir))

(use-fixtures :once
              (testutils/with-puppet-conf
               (fs/file test-resources-dir "puppet.conf")))

(use-fixtures :each
              (fn [f]
                (purge-env-dir)
                (try
                  (f)
                  (finally
                    (purge-env-dir)))))

(def request-as-text-with-ssl (assoc testutils/ssl-request-options :as :text))

(defn get-all-tasks
  [env-name]
  (let [url (str "https://localhost:8140/puppet/v3/tasks"
                 (if env-name (str "?environment=" env-name)))]
    (try
      (http-client/get url request-as-text-with-ssl)
      (catch Exception e
        (throw (Exception. "tasks http get failed" e))))))

(defn get-task-details
  "task-name is the task's full name, e.g. 'apache::reboot'."
  [env-name full-task-name]
  (let [[module-name task-name]  (str/split full-task-name #"::")
        url (str "https://localhost:8140/puppet/v3/tasks/" module-name "/" task-name
                 (if env-name (str "?environment=" env-name)))]
    (try
      (http-client/get url request-as-text-with-ssl)
      (catch Exception e
        (throw (Exception. "task info http get failed" e))))))

(defn parse-response
  [response]
  (-> response :body json/parse-string))

(defn sort-tasks
  [tasks]
  (sort-by #(get % "name") tasks))

(def puppet-config
  (-> (bootstrap/load-dev-config-with-overrides {:jruby-puppet {:max-active-instances 1}})
      (ks/dissoc-in [:jruby-puppet
                     :environment-class-cache-enabled])))

(deftest ^:integration all-tasks-with-env
  (testing "full stack tasks listing smoke test"
    (bootstrap/with-puppetserver-running-with-config
      app
      puppet-config
      (do
        (testutils/write-tasks-files "apache" "announce" "echo 'Hi!'")
        (testutils/write-tasks-files "graphite" "install" "wheeee")
        (let [expected-response '({"name" "apache::announce"
                                   "environment" [{"name" "production"
                                                   "code_id" nil}]}
                                  {"name" "graphite::install"
                                   "environment" [{"name" "production"
                                                   "code_id" nil}]})
              response (get-all-tasks "production")]
          (testing "a successful status code is returned"
            (is (= 200 (:status response))
                (str
                  "unexpected status code for response, response: "
                  (ks/pprint-to-string response))))
          (testing "the expected response body is returned"
            (is (= expected-response
                   (sort-tasks (parse-response response))))))))))

(deftest ^:integration task-details
  (testing "full stack task metadata smoke test:"
    (bootstrap/with-puppetserver-running-with-config
      app
      puppet-config
      (let [metadata {"description" "This is a test task"
                      "output" "'Hello, world!'"}]
        (testutils/write-tasks-files "shell" "poc" "echo 'Hello, world!'" (json/encode metadata))

        (testing "on a successful request,"
          (let [response (get-task-details "production" "shell::poc")
                code (:status response)]
            (testing "a successful status code is returned"
              (is (= 200 code)
                  (str "unexpected status code " code " for response: "
                       (ks/pprint-to-string response))))

            (testing "the expected response body is returned"
              (let [expected-response {"metadata" metadata
                                       "files" [{"filename" "poc.sh"
                                                 "sha256" "f24ce8f82408237beebf1fadd8a3da74ebd44512c02eee5ec24cf536871359f7"
                                                 "size_bytes" 20
                                                 "uri" {"path" "/puppet/v3/file_content/tasks/shell/poc.sh"
                                                        "params" {"environment" "production"}}}]}]
                (is (= expected-response (parse-response response)))))))

        (testing "on a request that should error,"
          (let [assert-task-error (fn [status pattern env full-task-name]
                               (let [result (get-task-details env full-task-name)]
                                 (is (= status (:status result)))
                                 (is (re-find pattern (:body result)))))]
            (testutils/write-tasks-files "mysql" "test" "echo 'Hello, world!'" "This isn't JSON.")

            (testing "returns 404 when the environment does not exist"
              (assert-task-error 404 #"Could not find environment"
                                 "nopers" "shell::poc"))

            (testing "returns 404 when the module does not exist"
              (assert-task-error 404 #"Could not find module"
                                 "production" "nomodule::poc"))

            (testing "returns 404 when the module name is invalid"
              (assert-task-error 404 #"Could not find module"
                                 "production" "000::poc"))

            (testing "returns 404 when the task does not exist"
              (assert-task-error 404 #"Could not find task"
                                 "production" "shell::notask"))

            (testing "returns 404 when the task name is invalid"
              (assert-task-error 404 #"Could not find task"
                                 "production" "shell::..."))

            (testing "returns 500 when the metadata file is unparseable"
              (assert-task-error 500 #"invalid-task-metadata"
                                 "production" "mysql::test"))))))))
